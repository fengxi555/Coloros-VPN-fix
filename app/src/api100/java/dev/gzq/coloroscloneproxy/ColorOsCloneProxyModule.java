package dev.gzq.coloroscloneproxy;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.SystemClock;
import android.provider.Settings;
import android.system.Os;
import android.system.StructStat;
import android.util.Log;
import android.util.Range;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerLoadedParam;
import io.github.libxposed.api.annotations.AfterInvocation;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

public final class ColorOsCloneProxyModule extends XposedModule {
    private static final String TAG = "CloneVpnFix";
    private static final String MODULE_VERSION = "2.5";
    private static final int PER_USER_RANGE = 100000;
    private static final int FIRST_NON_SYSTEM_UID = 10000;
    private static final int LAST_NON_SYSTEM_UID = 99999;
    private static final int FIRST_APPLICATION_UID = 10000;
    private static final int LAST_APPLICATION_UID = 19999;
    private static final int FIRST_SDK_SANDBOX_UID = 20000;
    private static final int COLOROS_CLONE_MIN_USER = 900;
    private static final int COLOROS_CLONE_MAX_USER = 999;
    private static final int DATA_USER_WATCH_EVENTS = FileObserver.CREATE
            | FileObserver.DELETE
            | FileObserver.MOVED_FROM
            | FileObserver.MOVED_TO;
    private static final long CONFIG_CACHE_MS = 2000L;
    private static final int STATUS_RETRY_COUNT = 24;
    private static final long STATUS_RETRY_INTERVAL_MS = 5000L;
    private static final int PRIVATE_LOG_FAILED = -1;
    private static final int PRIVATE_LOG_DISABLED = 0;
    private static final int PRIVATE_LOG_WRITTEN = 1;
    private static final int LEGACY_LOG_FALLBACK_SIZE = 512;
    private static final String LEGACY_SETTING_LOG_SEQ = "clonevpnfix.log.seq";
    private static final String LEGACY_SETTING_LOG_BOOT = "clonevpnfix.log.boot";
    private static final String LEGACY_SETTING_LOG_PREFIX = "clonevpnfix.log.";

    private static volatile ColorOsCloneProxyModule activeModule;

    private final XposedInterface xposedBase;
    private final Set<String> installedHooks = ConcurrentHashMap.newKeySet();
    private final AtomicInteger diagnosticLogSeq = new AtomicInteger(-1);
    private final String logBootId = currentBootId();
    private volatile Config cachedConfig;
    private volatile long configCacheUntil;
    private volatile int hookedMethods;
    private volatile int lastExpandedRanges;
    private volatile String lastTargetUsers = "none";
    private volatile List<UserRecord> cachedUsers = Collections.emptyList();
    private volatile boolean usersCacheReady;
    private volatile String lastState = "booting";
    private volatile String lastDetail = "waiting";
    private volatile boolean statusPublisherStarted;

    private volatile boolean privateLogAppendFailureLogged;
    private volatile boolean legacyLogCacheCleared;
    private volatile FileObserver dataUserObserver;

    public ColorOsCloneProxyModule(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
        xposedBase = base;
        activeModule = this;
        xLog(Log.INFO, "loaded on " + getFrameworkName() + " " + getFrameworkVersion()
                + ", api=" + runtimeApiVersion());
        moduleLog("module loaded process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " api=" + runtimeApiVersion());
    }

    @Override
    public void onSystemServerLoaded(SystemServerLoadedParam param) {
        moduleLog("onSystemServerLoaded framework=" + getFrameworkName() + " "
                + getFrameworkVersion() + " api=" + runtimeApiVersion());
        if (installVpnHooks(param.getClassLoader())) {
            startDataUserObserver();
            refreshDataUserTargets("boot");
            publishHookStatus("boot");
        }
    }

    private boolean installVpnHooks(ClassLoader classLoader) {
        Class<?> vpnClass;
        try {
            vpnClass = Class.forName("com.android.server.connectivity.Vpn", false, classLoader);
            moduleLog("Vpn class found: " + vpnClass.getName());
        } catch (Throwable t) {
            xLog(Log.ERROR, "Vpn class not found", t);
            moduleLog("Vpn class not found: " + t);
            publishStatus("error", "Vpn class not found");
            return false;
        }

        int count = 0;
        for (Method method : vpnClass.getDeclaredMethods()) {
            if (!looksLikeUidRangeFactory(method)) {
                continue;
            }
            String key = method.toGenericString();
            if (!installedHooks.add(key)) {
                continue;
            }
            try {
                method.setAccessible(true);
                hook(method, XposedInterface.PRIORITY_HIGHEST, UidRangeHooker.class);
                count++;
                xLog(Log.INFO, "hooked " + key);
                moduleLog("hooked method: " + key);
            } catch (Throwable t) {
                xLog(Log.WARN, "failed to hook " + key, t);
                moduleLog("failed to hook method: " + key + " error=" + t);
            }
        }
        hookedMethods = count;
        int diagnosticHooks = installVpnDiagnosticHooks(vpnClass);
        moduleLog("installVpnHooks completed hookedMethods=" + count
                + " diagnosticHooks=" + diagnosticHooks);
        if (count == 0) {
            publishStatus("error", "no VPN uid range method hooked");
            return false;
        }
        return true;
    }

    private int installVpnDiagnosticHooks(Class<?> vpnClass) {
        int count = 0;
        for (Method method : vpnClass.getDeclaredMethods()) {
            if (!looksLikeVpnDiagnosticMethod(method)) {
                continue;
            }
            String key = "diag:" + method.toGenericString();
            if (!installedHooks.add(key)) {
                continue;
            }
            try {
                method.setAccessible(true);
                hook(method, XposedInterface.PRIORITY_LOWEST, DiagnosticHooker.class);
                count++;
                moduleLog("hooked diagnostic method: " + method.toGenericString());
            } catch (Throwable t) {
                moduleLog("failed to hook diagnostic method: " + method.toGenericString()
                        + " error=" + t);
            }
        }
        return count;
    }

    private boolean looksLikeUidRangeFactory(Method method) {
        if (!Set.class.isAssignableFrom(method.getReturnType())) {
            return false;
        }
        String name = method.getName().toLowerCase(Locale.ROOT);
        if (!name.contains("range")) {
            return false;
        }
        for (Class<?> type : method.getParameterTypes()) {
            if (type == int.class || type == Integer.class) {
                return true;
            }
        }
        return "createuserandrestrictedprofilesranges".equals(name);
    }

    private boolean looksLikeVpnDiagnosticMethod(Method method) {
        String name = method.getName().toLowerCase(Locale.ROOT);
        if (name.contains("range")) {
            return false;
        }
        return name.contains("establish")
                || name.contains("startnewnetworkagent")
                || name.contains("agentconnect");
    }

    private void beforeVpnDiagnostic(Method method,
            XposedInterface.BeforeHookCallback callback) {
        if (!isDiagnosticLoggingEnabled()) {
            return;
        }
        String methodName = method.getName();
        moduleLog("VPN diag enter method=" + methodName
                + " args=" + summarizeArgs(callback.getArgs()));
    }

    private void afterVpnDiagnostic(Method method,
            XposedInterface.AfterHookCallback callback) {
        if (!isDiagnosticLoggingEnabled()) {
            return;
        }
        String methodName = method.getName();
        Throwable throwable = callback.getThrowable();
        moduleLog("VPN diag exit method=" + methodName
                + (throwable == null
                        ? " result=" + summarizeObject(callback.getResult())
                        : " throwable=" + throwable.getClass().getSimpleName()));
        Object thisObject = callback.getThisObject();
        if (shouldLogVpnCaps(methodName) && thisObject != null) {
            moduleLog("VPN diag caps method=" + methodName
                    + " caps=" + describeNetworkCaps(thisObject));
        }
    }

    private boolean shouldLogVpnCaps(String methodName) {
        if (methodName == null) {
            return false;
        }
        String name = methodName.toLowerCase(Locale.ROOT);
        return name.contains("establish")
                || name.contains("agent")
                || name.contains("networkcap")
                || name.contains("capabilit");
    }

    private void afterUidRangeFactory(Method method,
            XposedInterface.AfterHookCallback callback) {
        if (callback.getThrowable() != null) {
            return;
        }
        Object result = callback.getResult();
        if (!(result instanceof Set<?>)) {
            return;
        }

        Config config = config();
        if (!config.enabled) {
            return;
        }

        Object vpn = callback.getThisObject();
        int sourceUserId = resolveSourceUserId(vpn, callback.getArgs());
        Set<Integer> targetUserIds = targetUserIds(config, sourceUserId, vpn);
        moduleLog("VPN range call method=" + method.getName()
                + " sourceUser=" + sourceUserId
                + " targets=" + formatUsers(targetUserIds)
                + " sourceRanges=" + ((Set<?>) result).size());
        if (targetUserIds.isEmpty()) {
            return;
        }

        Set<?> sourceRanges = (Set<?>) result;
        moduleLog("VPN source ranges=" + summarizeRanges(sourceRanges));
        moduleLog("VPN config=" + describeVpnConfig(vpn));
        moduleLog("VPN netcaps(pre)=" + describeNetworkCaps(vpn));
        moduleLog("Via data dirs=" + describeViaDataDirs(targetUserIds));
        moduleLog("Via proc uids=" + describeViaProcesses());
        Set<Object> expanded = expandRanges(sourceRanges, sourceUserId, targetUserIds,
                readAllowedApplications(vpn), readDisallowedApplications(vpn), vpnContext(vpn));
        int added = expanded.size() - sourceRanges.size();
        moduleLog("VPN range expand sourceUser=" + sourceUserId
                + " targetUsers=" + formatUsers(targetUserIds)
                + " added=" + added
                + " before=" + sourceRanges.size()
                + " after=" + expanded.size());
        moduleLog("VPN range overlap=" + describeOverlaps(expanded));
        if (added <= 0) {
            return;
        }

        lastExpandedRanges = added;
        publishStatus("active", "expanded=" + added + ", users=" + formatUsers(targetUserIds));
        if (config.log) {
            xLog(Log.INFO, "expanded VPN uid ranges by " + added
                    + " for users " + formatUsers(targetUserIds));
        }
        callback.setResult(expanded);
    }

    @XposedHooker
    public static final class UidRangeHooker implements XposedInterface.Hooker {
        private UidRangeHooker() {
        }

        @AfterInvocation
        public static void after(XposedInterface.AfterHookCallback callback) {
            ColorOsCloneProxyModule module = activeModule;
            Member member = callback.getMember();
            if (module != null && member instanceof Method) {
                module.afterUidRangeFactory((Method) member, callback);
            }
        }
    }

    @XposedHooker
    public static final class DiagnosticHooker implements XposedInterface.Hooker {
        private DiagnosticHooker() {
        }

        @BeforeInvocation
        public static void before(XposedInterface.BeforeHookCallback callback) {
            ColorOsCloneProxyModule module = activeModule;
            Member member = callback.getMember();
            if (module != null && member instanceof Method) {
                module.beforeVpnDiagnostic((Method) member, callback);
            }
        }

        @AfterInvocation
        public static void after(XposedInterface.AfterHookCallback callback) {
            ColorOsCloneProxyModule module = activeModule;
            Member member = callback.getMember();
            if (module != null && member instanceof Method) {
                module.afterVpnDiagnostic((Method) member, callback);
            }
        }
    }

    private Set<Object> expandRanges(Set<?> sourceRanges, int sourceUserId, Set<Integer> targets,
            List<String> allowedApplications, List<String> disallowedApplications, Context context) {
        Set<Object> expanded = new LinkedHashSet<>();
        Object template = null;
        List<String> samples = new ArrayList<>();

        for (Object sourceRange : sourceRanges) {
            expanded.add(sourceRange);
            if (template == null && readUidRange(sourceRange) != null) {
                template = sourceRange;
            }
        }

        if (template == null) {
            return expanded;
        }

        boolean useAllowedApplications = allowedApplications != null;
        boolean useDisallowedApplications = !useAllowedApplications
                && disallowedApplications != null
                && !disallowedApplications.isEmpty();
        for (int userId : targets) {
            if (userId == sourceUserId) {
                continue;
            }
            if (useAllowedApplications) {
                addAllowedApplicationRanges(expanded, template, samples, context, userId,
                        allowedApplications);
            } else {
                addMirroredSourceRanges(expanded, template, samples, sourceRanges, sourceUserId, userId);
            }
        }
        String strategy = useAllowedApplications ? "allowed-apps"
                : (useDisallowedApplications ? "mirror-source-disallowed" : "mirror-source");
        int packageCount = useAllowedApplications ? allowedApplications.size()
                : (useDisallowedApplications ? disallowedApplications.size() : 0);
        moduleLog("range strategy=" + strategy
                + (packageCount > 0 ? ", packages=" + packageCount : "")
                + ", addedSamples=" + joinSamples(samples, 12));
        return expanded;
    }

    private void addAllowedApplicationRanges(Set<Object> ranges, Object template, List<String> samples,
            Context context, int userId, List<String> allowedApplications) {
        List<Integer> uids = appUidsForUser(context, allowedApplications, userId);
        if (uids.isEmpty()) {
            return;
        }

        int start = -1;
        int stop = -1;
        for (int uid : uids) {
            if (start < 0) {
                start = uid;
                stop = uid;
            } else if (uid == stop + 1) {
                stop = uid;
            } else {
                addRawUidRange(ranges, template, samples, start, stop);
                start = uid;
                stop = uid;
            }
        }
        if (start >= 0) {
            addRawUidRange(ranges, template, samples, start, stop);
        }
    }

    private void addRawUidRange(Set<Object> ranges, Object template, List<String> samples,
            int start, int stop) {
        if (start > stop) {
            return;
        }
        Object newRange = createUidRangeLike(template, start, stop);
        if (newRange != null) {
            ranges.add(newRange);
            if (samples.size() < 16) {
                samples.add(start + "-" + stop);
            }
        }
    }

    private void addTargetUserRange(Set<Object> ranges, Object template, List<String> samples, int userId,
            int appStart, int appStop) {
        int userBase = userId * PER_USER_RANGE;
        int start = userBase + appStart;
        int stop = userBase + appStop;
        addRawUidRange(ranges, template, samples, start, stop);
    }

    private void addMirroredSourceRanges(Set<Object> ranges, Object template, List<String> samples,
            Set<?> sourceRanges, int sourceUserId, int targetUserId) {
        int sourceBase = sourceUserId * PER_USER_RANGE;
        int sourceMin = sourceBase + FIRST_NON_SYSTEM_UID;
        int sourceMax = sourceBase + LAST_NON_SYSTEM_UID;
        for (Object raw : sourceRanges) {
            UidRange source = readUidRange(raw);
            if (source == null) {
                continue;
            }
            int start = Math.max(source.start, sourceMin);
            int stop = Math.min(source.stop, sourceMax);
            if (start > stop) {
                continue;
            }
            addTargetUserRange(ranges, template, samples, targetUserId,
                    start - sourceBase, stop - sourceBase);
        }
    }

    private List<Integer> appUidsForUser(Context context, List<String> packageNames, int userId) {
        if (context == null || packageNames == null || packageNames.isEmpty()) {
            return Collections.emptyList();
        }

        TreeSet<Integer> uids = new TreeSet<>();
        for (String packageName : packageNames) {
            int uid = getPackageUidAsUser(context, packageName, userId);
            if (uid < 0) {
                continue;
            }
            uids.add(uid);
            int sdkSandboxUid = sdkSandboxUidFor(uid);
            if (sdkSandboxUid >= 0) {
                uids.add(sdkSandboxUid);
            }
        }
        return new ArrayList<>(uids);
    }

    private int getPackageUidAsUser(Context context, String packageName, int userId) {
        if (context == null || packageName == null || packageName.isEmpty()) {
            return -1;
        }

        Object packageManager = context.getPackageManager();
        long token = Binder.clearCallingIdentity();
        try {
            try {
                Method method = packageManager.getClass()
                        .getMethod("getPackageUidAsUser", String.class, int.class);
                Object value = method.invoke(packageManager, packageName, userId);
                return value instanceof Integer ? (Integer) value : -1;
            } catch (NoSuchMethodException ignored) {
                Method method = packageManager.getClass()
                        .getMethod("getPackageUidAsUser", String.class, int.class, int.class);
                Object value = method.invoke(packageManager, packageName, 0, userId);
                return value instanceof Integer ? (Integer) value : -1;
            }
        } catch (Throwable ignored) {
            return -1;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private int sdkSandboxUidFor(int uid) {
        if (uid < 0) {
            return -1;
        }
        try {
            Class<?> process = Class.forName("android.os.Process");
            Object isApp = process.getMethod("isApplicationUid", int.class).invoke(null, uid);
            if (Boolean.TRUE.equals(isApp)) {
                Object sandboxUid = process.getMethod("toSdkSandboxUid", int.class).invoke(null, uid);
                if (sandboxUid instanceof Integer) {
                    return (Integer) sandboxUid;
                }
            }
        } catch (Throwable ignored) {
            // Fall back to the platform UID layout used on Android 13+.
        }

        int appId = uid % PER_USER_RANGE;
        if (appId < FIRST_APPLICATION_UID || appId > LAST_APPLICATION_UID) {
            return -1;
        }
        int userId = uid / PER_USER_RANGE;
        return userId * PER_USER_RANGE + FIRST_SDK_SANDBOX_UID + (appId - FIRST_APPLICATION_UID);
    }

    private String summarizeRanges(Set<?> ranges) {
        List<String> samples = new ArrayList<>();
        int readable = 0;
        for (Object raw : ranges) {
            UidRange range = readUidRange(raw);
            if (range == null) {
                continue;
            }
            readable++;
            if (samples.size() < 12) {
                samples.add(range.start + "-" + range.stop);
            }
        }
        return "count=" + ranges.size() + ", readable=" + readable
                + ", samples=" + joinSamples(samples, 12);
    }

    private String summarizeArgs(Object[] args) {
        return summarizeArgs(args == null ? null : Arrays.asList(args));
    }

    private String summarizeArgs(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return "none";
        }
        List<String> samples = new ArrayList<>();
        int count = Math.min(args.size(), 8);
        for (int i = 0; i < count; i++) {
            samples.add(i + "=" + summarizeObject(args.get(i)));
        }
        return "count=" + args.size() + " " + joinSamples(samples, 8);
    }

    private String summarizeObject(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return shortenCmdline(String.valueOf(value));
        }
        if (value instanceof Set<?>) {
            return "Set(size=" + ((Set<?>) value).size() + ")";
        }
        if (value instanceof List<?>) {
            return "List(size=" + ((List<?>) value).size() + ")";
        }
        if (looksLikeNetworkCapabilities(value)) {
            return "NetworkCapabilities(" + describeNetworkCapabilitiesObject(value) + ")";
        }
        UidRange range = readUidRange(value);
        if (range != null) {
            return range.start + "-" + range.stop;
        }
        return value.getClass().getSimpleName();
    }

    private String joinSamples(List<String> samples, int limit) {
        if (samples == null || samples.isEmpty()) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        int count = Math.min(samples.size(), limit);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(';');
            }
            builder.append(samples.get(i));
        }
        if (samples.size() > limit) {
            builder.append(";...");
        }
        return builder.toString();
    }

    private String describeVpnConfig(Object vpn) {
        if (vpn == null) {
            return "vpn-null";
        }
        Object config = readField(vpn, "mConfig");
        if (config == null) {
            return "mConfig-null";
        }
        String allowed = describeAppList(readField(config, "allowedApplications"));
        String disallowed = describeAppList(readField(config, "disallowedApplications"));
        Object session = readField(config, "session");
        Object user = readField(config, "user");
        Boolean allowBypass = readBooleanField(config, "allowBypass");
        return "session=" + session
                + ", configUser=" + user
                + ", allowBypass=" + allowBypass
                + ", allowed=" + allowed
                + ", disallowed=" + disallowed;
    }

    private String describeAppList(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            int count = list.size();
            StringBuilder builder = new StringBuilder("size=").append(count).append("[");
            int limit = Math.min(count, 8);
            for (int i = 0; i < limit; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(String.valueOf(list.get(i)));
            }
            if (count > limit) {
                builder.append(",...");
            }
            builder.append(']');
            return builder.toString();
        }
        return value.getClass().getSimpleName();
    }

    private String describeOverlaps(Set<Object> ranges) {
        List<UidRange> parsed = new ArrayList<>();
        for (Object raw : ranges) {
            UidRange range = readUidRange(raw);
            if (range != null) {
                parsed.add(range);
            }
        }
        parsed.sort((a, b) -> Integer.compare(a.start, b.start));

        List<String> overlaps = new ArrayList<>();
        for (int i = 1; i < parsed.size(); i++) {
            UidRange prev = parsed.get(i - 1);
            UidRange cur = parsed.get(i);
            if (cur.start <= prev.stop) {
                if (overlaps.size() < 12) {
                    overlaps.add(prev.start + "-" + prev.stop + " vs " + cur.start + "-" + cur.stop);
                }
            }
        }
        return overlaps.isEmpty()
                ? "none readable=" + parsed.size()
                : "count=" + overlaps.size() + " " + joinSamples(overlaps, 12);
    }

    private Object readField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean readBooleanField(Object target, String fieldName) {
        Object value = readField(target, fieldName);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private String describeNetworkCaps(Object vpn) {
        Object caps = readField(vpn, "mNetworkCapabilities");
        if (caps == null) {
            return "null";
        }
        return describeNetworkCapabilitiesObject(caps);
    }

    private String describeNetworkCapabilitiesObject(Object caps) {
        Object uidRanges = readField(caps, "mUids");
        if (uidRanges == null) {
            return "mUids-null";
        }
        if (!(uidRanges instanceof Set<?>)) {
            return uidRanges.getClass().getSimpleName();
        }
        Set<?> set = (Set<?>) uidRanges;
        List<String> samples = new ArrayList<>();
        for (Object raw : set) {
            if (samples.size() >= 32) {
                break;
            }
            UidRange range = readUidRange(raw);
            if (range != null) {
                samples.add(range.start + "-" + range.stop);
            } else {
                samples.add(String.valueOf(raw));
            }
        }
        return "count=" + set.size() + ", samples=" + joinSamples(samples, 32);
    }

    private boolean looksLikeNetworkCapabilities(Object value) {
        return value != null && "android.net.NetworkCapabilities".equals(value.getClass().getName());
    }

    private List<String> readAllowedApplications(Object vpn) {
        Object config = readField(vpn, "mConfig");
        Object value = readField(config, "allowedApplications");
        if (!(value instanceof List<?>)) {
            return null;
        }

        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof String) {
                result.add((String) item);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private List<String> readDisallowedApplications(Object vpn) {
        Object config = readField(vpn, "mConfig");
        Object value = readField(config, "disallowedApplications");
        if (!(value instanceof List<?>)) {
            return null;
        }

        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof String) {
                result.add((String) item);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private Context vpnContext(Object vpn) {
        Object value = readField(vpn, "mContext");
        if (value instanceof Context) {
            return (Context) value;
        }
        return getSystemContext();
    }

    private int resolveSourceUserId(Object thisObject, Object[] args) {
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Integer) {
                    int userId = (Integer) arg;
                    if (userId >= 0 && userId < 10000) {
                        return userId;
                    }
                }
            }
        }
        Integer userId = readIntField(thisObject, "mUserId");
        return userId == null ? 0 : userId;
    }

    private Set<Integer> targetUserIds(Config config, int sourceUserId, Object vpn) {
        if (!config.users.isEmpty()) {
            Set<Integer> explicit = new LinkedHashSet<>(config.users);
            explicit.remove(sourceUserId);
            if (sourceUserId == 0) {
                lastTargetUsers = formatUsers(explicit);
            }
            moduleLog("targetUserIds explicit source=" + sourceUserId
                    + " users=" + formatUsers(explicit));
            return explicit;
        }

        Set<Integer> result = new LinkedHashSet<>();
        for (UserRecord user : readUsers(vpn)) {
            if (user.id == sourceUserId) {
                continue;
            }
            switch (config.mode) {
                case "all":
                    result.add(user.id);
                    break;
                case "clone":
                    if (user.isClone) {
                        result.add(user.id);
                    }
                    break;
                case "non_owner":
                default:
                    if (user.id != 0) {
                        result.add(user.id);
                    }
                    break;
            }
        }
        moduleLog("targetUserIds mode=" + config.mode + " source=" + sourceUserId
                + " result=" + formatUsers(result));
        if (sourceUserId == 0) {
            lastTargetUsers = formatUsers(result);
        }
        return result;
    }

    private List<UserRecord> readUsers(Object vpn) {
        UserDiscoveryResult aliveUsers = readAliveCloneUsers(vpn);
        if (aliveUsers.available) {
            if (!aliveUsers.users.isEmpty()) {
                moduleLog("user discovery source=alive users=" + formatUserRecords(aliveUsers.users));
                return aliveUsers.users;
            }
            moduleLog("user discovery source=alive empty fallback=/data/user");
        } else if (vpn != null) {
            moduleLog("user discovery source=alive unavailable detail=" + aliveUsers.detail
                    + " fallback=/data/user");
        }

        if (!usersCacheReady) {
            refreshDataUserTargets("lazy");
        }
        return cachedUsers;
    }

    private UserDiscoveryResult readAliveCloneUsers(Object vpn) {
        Object userManager = readField(vpn, "mUserManager");
        if (userManager == null) {
            return UserDiscoveryResult.unavailable("mUserManager-null");
        }

        long token = Binder.clearCallingIdentity();
        try {
            Method method = userManager.getClass().getMethod("getAliveUsers");
            method.setAccessible(true);
            Object value = method.invoke(userManager);
            if (!(value instanceof List<?>)) {
                return UserDiscoveryResult.unavailable("getAliveUsers-non-list");
            }

            Map<Integer, Boolean> users = new LinkedHashMap<>();
            for (Object raw : (List<?>) value) {
                Integer userId = readUserInfoId(raw);
                if (userId != null && looksLikeColorOsCloneUser(userId)) {
                    users.put(userId, true);
                }
            }
            return UserDiscoveryResult.available(toUserRecords(users));
        } catch (Throwable t) {
            return UserDiscoveryResult.unavailable(t.getClass().getSimpleName());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private Integer readUserInfoId(Object userInfo) {
        Object value = readField(userInfo, "id");
        if (value instanceof Integer) {
            return (Integer) value;
        }
        return invokeInt(userInfo, "getIdentifier");
    }

    private void refreshDataUserTargets(String reason) {
        Map<Integer, Boolean> users = new LinkedHashMap<>();
        scanUserDir(users, "/data/user");
        cachedUsers = toUserRecords(users);
        usersCacheReady = true;
        moduleLog("/data/user scan reason=" + reason + " users=" + formatUserMap(users));
        moduleLog("Via data dirs=" + describeViaDataDirs(users.keySet()));
    }

    private List<UserRecord> toUserRecords(Map<Integer, Boolean> users) {
        List<UserRecord> result = new ArrayList<>(users.size());
        for (Map.Entry<Integer, Boolean> entry : users.entrySet()) {
            result.add(new UserRecord(entry.getKey(), entry.getValue()));
        }
        return Collections.unmodifiableList(result);
    }

    private void scanUserDir(Map<Integer, Boolean> users, String path) {
        File root = new File(path);
        File[] children;
        try {
            children = root.listFiles();
        } catch (Throwable ignored) {
            moduleLog("scanUserDir failed path=" + path + " error=" + ignored);
            return;
        }
        if (children == null) {
            moduleLog("scanUserDir empty/null path=" + path);
            return;
        }
        List<Integer> found = new ArrayList<>();
        for (File child : children) {
            String name = child.getName();
            int userId = parseUserIdName(name);
            if (!looksLikeColorOsCloneUser(userId)) {
                continue;
            }
            users.put(userId, true);
            found.add(userId);
        }
        moduleLog("scanUserDir path=" + path + " found=" + found);
    }

    private int parseUserIdName(String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) {
                return -1;
            }
        }
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private boolean looksLikeColorOsCloneUser(int userId) {
        return userId >= COLOROS_CLONE_MIN_USER && userId <= COLOROS_CLONE_MAX_USER;
    }

    private String describeTargetUsers() {
        Config config = config();
        Set<Integer> users = targetUserIds(config, 0, null);
        return formatUsers(users);
    }

    private String formatUsers(Set<Integer> users) {
        if (users == null || users.isEmpty()) {
            return "none";
        }
        List<Integer> sorted = new ArrayList<>(users);
        Collections.sort(sorted);
        StringBuilder builder = new StringBuilder("[");
        int start = sorted.get(0);
        int previous = start;
        for (int i = 1; i < sorted.size(); i++) {
            int current = sorted.get(i);
            if (current == previous + 1) {
                previous = current;
                continue;
            }
            appendUserRange(builder, start, previous);
            builder.append(", ");
            start = current;
            previous = current;
        }
        appendUserRange(builder, start, previous);
        builder.append(']');
        return builder.toString();
    }

    private String formatUserMap(Map<Integer, Boolean> users) {
        if (users == null || users.isEmpty()) {
            return "none";
        }
        return formatUsers(users.keySet());
    }

    private String formatUserRecords(List<UserRecord> users) {
        if (users == null || users.isEmpty()) {
            return "none";
        }
        Set<Integer> ids = new LinkedHashSet<>();
        for (UserRecord user : users) {
            ids.add(user.id);
        }
        return formatUsers(ids);
    }

    private void appendUserRange(StringBuilder builder, int start, int end) {
        if (start == end) {
            builder.append(start);
        } else {
            builder.append(start).append('-').append(end);
        }
    }

    private Config config() {
        long now = SystemClock.uptimeMillis();
        Config current = cachedConfig;
        if (current != null && now < configCacheUntil) {
            return current;
        }

        synchronized (this) {
            current = cachedConfig;
            now = SystemClock.uptimeMillis();
            if (current != null && now < configCacheUntil) {
                return current;
            }
            current = Config.read();
            cachedConfig = current;
            configCacheUntil = now + CONFIG_CACHE_MS;
            return current;
        }
    }

    private void startDataUserObserver() {
        if (dataUserObserver != null) {
            return;
        }
        synchronized (this) {
            if (dataUserObserver != null) {
                return;
            }
            try {
                FileObserver observer = new FileObserver("/data/user", DATA_USER_WATCH_EVENTS) {
                    @Override
                    public void onEvent(int event, String path) {
                        handleDataUserEvent(event, path);
                    }
                };
                observer.startWatching();
                dataUserObserver = observer;
                moduleLog("/data/user FileObserver started events=" + DATA_USER_WATCH_EVENTS);
            } catch (Throwable t) {
                moduleLog("/data/user FileObserver failed: " + t);
            }
        }
    }

    private void handleDataUserEvent(int event, String path) {
        if ((event & DATA_USER_WATCH_EVENTS) == 0) {
            return;
        }
        int userId = parseUserIdName(path);
        if (!looksLikeColorOsCloneUser(userId)) {
            return;
        }

        refreshDataUserTargets(dataUserEventName(event) + ":" + userId);
        Config current = config();
        Set<Integer> targets = targetUserIds(current, 0, null);
        moduleLog("/data/user changed event=" + dataUserEventName(event)
                + " user=" + userId
                + " targets=" + formatUsers(targets)
                + " vpnRangeRefresh=requires-vpn-recreate");
        publishHookStatus("users=" + formatUsers(targets));
    }

    private String dataUserEventName(int event) {
        if ((event & FileObserver.CREATE) != 0) {
            return "CREATE";
        }
        if ((event & FileObserver.DELETE) != 0) {
            return "DELETE";
        }
        if ((event & FileObserver.MOVED_TO) != 0) {
            return "MOVED_TO";
        }
        if ((event & FileObserver.MOVED_FROM) != 0) {
            return "MOVED_FROM";
        }
        return String.valueOf(event);
    }

    private UidRange readUidRange(Object range) {
        if (range == null) {
            return null;
        }
        try {
            if (range instanceof Range<?>) {
                Range<?> androidRange = (Range<?>) range;
                Object lower = androidRange.getLower();
                Object upper = androidRange.getUpper();
                if (lower instanceof Integer && upper instanceof Integer) {
                    return new UidRange((Integer) lower, (Integer) upper);
                }
            }

            Integer start = readIntField(range, "start");
            Integer stop = readIntField(range, "stop");
            if (start != null && stop != null) {
                return new UidRange(start, stop);
            }

            start = invokeInt(range, "getStart");
            stop = invokeInt(range, "getStop");
            if (start != null && stop != null) {
                return new UidRange(start, stop);
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private Object createUidRangeLike(Object template, int start, int stop) {
        Class<?> type = template.getClass();
        String className = type.getName();
        try {
            if ("android.util.Range".equals(className)) {
                return new Range<>(start, stop);
            }

            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length == 2
                        && parameterTypes[0] == int.class
                        && parameterTypes[1] == int.class) {
                    constructor.setAccessible(true);
                    return constructor.newInstance(start, stop);
                }
            }

            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object range = constructor.newInstance();
            setIntField(range, "start", start);
            setIntField(range, "stop", stop);
            return range;
        } catch (Throwable t) {
            if (config().log) {
                xLog(Log.WARN, "failed to create uid range like " + className, t);
            }
            return null;
        }
    }

    private void publishStatus(String state, String detail) {
        lastState = state;
        lastDetail = detail;
        if (!writeStatusSnapshot()) {
            startStatusPublisher();
        }
    }

    private void publishHookStatus(String reason) {
        Config current = config();
        if (!current.enabled) {
            publishStatus("inactive", "module disabled");
            return;
        }
        if (hookedMethods <= 0) {
            publishStatus("error", "no VPN uid range method hooked");
            return;
        }

        Set<Integer> targets = targetUserIds(current, 0, null);
        String detail = "hooked=" + hookedMethods + ", users=" + formatUsers(targets);
        if (reason != null && !reason.isEmpty()) {
            detail += ", reason=" + reason;
        }
        publishStatus("active", detail);
    }

    private void startStatusPublisher() {
        if (statusPublisherStarted) {
            return;
        }
        synchronized (this) {
            if (statusPublisherStarted) {
                return;
            }
            statusPublisherStarted = true;
        }
        Thread thread = new Thread(() -> {
            for (int i = 0; i < STATUS_RETRY_COUNT; i++) {
                if (writeStatusSnapshot()) {
                    return;
                }
                try {
                    Thread.sleep(STATUS_RETRY_INTERVAL_MS);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }, "CloneVpnFixStatus");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean writeStatusSnapshot() {
        String active = "active".equals(lastState) ? "1" : "0";
        String framework = getFrameworkName() + " " + getFrameworkVersion();
        String api = String.valueOf(runtimeApiVersion());
        String hooks = String.valueOf(hookedMethods);
        String ranges = String.valueOf(lastExpandedRanges);
        String bootId = logBootId;
        String detail = statusDetail();

        Context context = getSystemContext();
        boolean wroteSettings = false;
        wroteSettings |= putStatus(context, ModuleProps.SETTING_ACTIVE, ModuleProps.PROP_ACTIVE, active);
        wroteSettings |= putStatus(context, ModuleProps.SETTING_STATE, ModuleProps.PROP_STATE, lastState);
        wroteSettings |= putStatus(context, ModuleProps.SETTING_DETAIL, ModuleProps.PROP_DETAIL, detail);
        wroteSettings |= putStatus(context, ModuleProps.SETTING_VERSION, ModuleProps.PROP_VERSION, MODULE_VERSION);
        wroteSettings |= putStatus(context, ModuleProps.SETTING_API, ModuleProps.PROP_API, api);
        wroteSettings |= putStatus(context, ModuleProps.SETTING_FRAMEWORK, ModuleProps.PROP_FRAMEWORK, framework);
        wroteSettings |= putStatus(context, ModuleProps.SETTING_HOOKS, ModuleProps.PROP_HOOKS, hooks);
        wroteSettings |= putStatus(context, ModuleProps.SETTING_EXPANDED_RANGES, ModuleProps.PROP_EXPANDED_RANGES, ranges);
        wroteSettings |= putStatus(context, ModuleProps.SETTING_USERS, ModuleProps.PROP_USERS, lastTargetUsers);
        wroteSettings |= putStatus(context, ModuleProps.SETTING_BOOT_ID, ModuleProps.PROP_BOOT_ID, bootId);
        clearLegacySystemLogCache(context);
        return wroteSettings;
    }

    private String statusDetail() {
        if (!"active".equals(lastState)) {
            return lastDetail;
        }
        if (lastExpandedRanges > 0) {
            return "expanded=" + lastExpandedRanges + ", users=" + lastTargetUsers;
        }
        return "hooked=" + hookedMethods + ", users=" + lastTargetUsers;
    }

    private boolean putStatus(Context context, String settingKey, String propKey, String value) {
        boolean wrote = setProp(propKey, value);
        if (context == null) {
            return wrote;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            Settings.Global.putString(context.getContentResolver(), settingKey, value);
            return true;
        } catch (Throwable ignored) {
            // Best effort status only; routing Hook does not depend on this.
            return wrote;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Context getSystemContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return null;
            }
            Object context = activityThread.getMethod("getSystemContext").invoke(thread);
            return context instanceof Context ? (Context) context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void clearLegacySystemLogCache(Context context) {
        if (legacyLogCacheCleared || context == null) {
            return;
        }
        legacyLogCacheCleared = true;
        long identity = Binder.clearCallingIdentity();
        try {
            Settings.Global.putString(context.getContentResolver(), LEGACY_SETTING_LOG_SEQ, null);
            Settings.Global.putString(context.getContentResolver(), LEGACY_SETTING_LOG_BOOT, null);
            for (int i = 0; i < LEGACY_LOG_FALLBACK_SIZE; i++) {
                Settings.Global.putString(context.getContentResolver(),
                        LEGACY_SETTING_LOG_PREFIX + i, null);
            }
        } catch (Throwable ignored) {
            // Legacy cleanup only; current logging no longer depends on Settings.Global.
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static boolean setProp(String key, String value) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            systemProperties.getMethod("set", String.class, String.class)
                    .invoke(null, key, shortenProperty(value));
            return true;
        } catch (Throwable ignored) {
            // Best effort status only; Settings.Global is attempted separately.
            return false;
        }
    }

    private static String shortenProperty(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 90 ? value.substring(0, 90) : value;
    }

    private static Integer readIntField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setIntField(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static Integer invokeInt(Object target, String methodName) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Set<Integer> parseIntSet(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<Integer> result = new HashSet<>();
        for (String raw : csv.split(",")) {
            try {
                result.add(Integer.parseInt(raw.trim()));
            } catch (NumberFormatException ignored) {
                // Ignore malformed user ids.
            }
        }
        return result;
    }

    private static String getProp(String key, String fallback) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Object value = systemProperties
                    .getMethod("get", String.class, String.class)
                    .invoke(null, key, fallback);
            return value instanceof String ? (String) value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean getBooleanProp(String key, boolean fallback) {
        String value = getProp(key, fallback ? "1" : "0").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return fallback;
        }
        return "1".equals(value) || "true".equals(value) || "yes".equals(value) || "on".equals(value);
    }

    private int runtimeApiVersion() {
        try {
            Method method = xposedBase.getClass().getMethod("getApiVersion");
            Object value = method.invoke(xposedBase);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (Throwable ignored) {
            // API 100 frameworks usually do not expose the 101-style runtime API method.
        }
        try {
            Method method = xposedBase.getClass().getMethod("getAPIVersion");
            Object value = method.invoke(xposedBase);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (Throwable ignored) {
            // Fall through to the API 100 wrapper value.
        }
        return XposedInterface.API;
    }

    private void xLog(int priority, String message) {
        Log.println(priority, TAG, message);
        log(TAG + "/" + priority + ": " + message);
    }

    private void xLog(int priority, String message, Throwable throwable) {
        Log.println(priority, TAG, message + "\n" + Log.getStackTraceString(throwable));
        log(TAG + "/" + priority + ": " + message, throwable);
    }

    private void moduleLog(String message) {
        diagnosticLog(message, config().log);
    }

    private boolean isDiagnosticLoggingEnabled() {
        return true;
    }

    private void diagnosticLog(String message, boolean forceXposedLog) {
        int seq = nextDiagnosticLogSeq();
        String line = shortenSystemLog("boot=" + logBootId
                + " seq=" + seq
                + " t=" + SystemClock.elapsedRealtime()
                + " " + message);

        int privateLog = appendPrivateLog(line);
        if (privateLog == PRIVATE_LOG_WRITTEN) {
            return;
        }
        if (forceXposedLog) {
            xLog(Log.INFO, "diag seq=" + seq + " privateLog=" + privateLog + " " + line);
        }
    }

    private int appendPrivateLog(String line) {
        Context context = getSystemContext();
        if (context == null) {
            return PRIVATE_LOG_FAILED;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            Bundle extras = new Bundle();
            extras.putString(ModuleProps.LOG_EXTRA_MESSAGE, line);
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://" + ModuleProps.LOG_AUTHORITY),
                    ModuleProps.LOG_METHOD_APPEND,
                    null,
                    extras);
            if (result == null) {
                logPrivateAppendFailure("private log append returned null");
                return appendPrivateLogViaBroadcast(context, line);
            }

            boolean enabled = result.getBoolean(ModuleProps.LOG_EXTRA_ENABLED, false);
            boolean ok = result.getBoolean(ModuleProps.LOG_EXTRA_OK, false);
            if (!enabled) {
                return PRIVATE_LOG_DISABLED;
            }
            if (!ok) {
                logPrivateAppendFailure("private log append returned ok=false");
                return appendPrivateLogViaBroadcast(context, line);
            }
            return PRIVATE_LOG_WRITTEN;
        } catch (Throwable ignored) {
            logPrivateAppendFailure("private log provider call failed");
            return appendPrivateLogViaBroadcast(context, line);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private int appendPrivateLogViaBroadcast(Context context, String line) {
        long identity = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent(ModuleProps.LOG_ACTION_APPEND);
            intent.setClassName(ModuleProps.PACKAGE_NAME, ModuleProps.LOG_RECEIVER_CLASS);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND
                    | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra(ModuleProps.LOG_EXTRA_MESSAGE, line);
            intent.putExtra(ModuleProps.LOG_EXTRA_CALLER_UID, android.os.Process.SYSTEM_UID);
            context.sendBroadcast(intent);
            return PRIVATE_LOG_WRITTEN;
        } catch (Throwable ignored) {
            logPrivateAppendFailure("private log broadcast fallback failed");
            return PRIVATE_LOG_FAILED;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void logPrivateAppendFailure(String message) {
        if (privateLogAppendFailureLogged) {
            return;
        }
        privateLogAppendFailureLogged = true;
        xLog(Log.WARN, message);
    }

    private int nextDiagnosticLogSeq() {
        int current = diagnosticLogSeq.get();
        if (current >= 0) {
            return diagnosticLogSeq.incrementAndGet();
        }

        synchronized (diagnosticLogSeq) {
            current = diagnosticLogSeq.get();
            if (current >= 0) {
                return diagnosticLogSeq.incrementAndGet();
            }
            diagnosticLogSeq.set(-1);
            return diagnosticLogSeq.incrementAndGet();
        }
    }

    private static String shortenSystemLog(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replace('\r', ' ').replace('\n', ' ');
        return compact.length() > 700 ? compact.substring(0, 700) : compact;
    }

    private String describeViaProcesses() {
        File proc = new File("/proc");
        File[] entries = proc.listFiles();
        if (entries == null) {
            return "proc-unavailable";
        }

        List<String> matches = new ArrayList<>();
        for (File entry : entries) {
            if (matches.size() >= 12) {
                break;
            }
            String pid = entry.getName();
            if (parseUserIdName(pid) < 0) {
                continue;
            }
            String cmdline = readSmallFile(new File(entry, "cmdline"), 256)
                    .replace('\0', ' ')
                    .trim();
            String lower = cmdline.toLowerCase(Locale.ROOT);
            if (!lower.contains("via")) {
                continue;
            }
            int uid = readProcessUid(new File(entry, "status"));
            matches.add("pid=" + pid + ",uid=" + uid + ",cmd=" + shortenCmdline(cmdline));
        }
        return matches.isEmpty() ? "none" : joinSamples(matches, 12);
    }

    private String describeViaDataDirs(Set<Integer> userIds) {
        List<String> matches = new ArrayList<>();
        for (int userId : userIds) {
            if (matches.size() >= 20) {
                break;
            }
            File root = new File("/data/user/" + userId);
            File[] children = root.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (matches.size() >= 20) {
                    break;
                }
                String name = child.getName();
                if (name == null || !name.toLowerCase(Locale.ROOT).contains("via")) {
                    continue;
                }
                int uid = readOwnerUid(child);
                matches.add("user=" + userId + ",uid=" + uid + ",dir=" + shortenCmdline(name));
            }
        }
        return matches.isEmpty() ? "none" : joinSamples(matches, 20);
    }

    private int readOwnerUid(File file) {
        try {
            StructStat stat = Os.stat(file.getAbsolutePath());
            return stat.st_uid;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private int readProcessUid(File status) {
        String content = readSmallFile(status, 2048);
        String[] lines = content.split("\\n");
        for (String line : lines) {
            if (!line.startsWith("Uid:")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) {
                return -1;
            }
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static String currentBootId() {
        String bootId = readSmallFile(new File("/proc/sys/kernel/random/boot_id"), 128).trim();
        return bootId.isEmpty() ? Long.toString(SystemClock.elapsedRealtime()) : bootId;
    }

    private static String readSmallFile(File file, int maxBytes) {
        byte[] buffer = new byte[maxBytes];
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            int read = input.read(buffer);
            if (read <= 0) {
                return "";
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String shortenCmdline(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static final class Config {
        final boolean enabled;
        final String mode;
        final Set<Integer> users;
        final boolean log;

        private Config(boolean enabled, String mode, Set<Integer> users, boolean log) {
            this.enabled = enabled;
            this.mode = mode;
            this.users = users;
            this.log = log;
        }

        static Config read() {
            String mode = getProp(ModuleProps.MODE, ModuleProps.DEFAULT_MODE)
                    .trim()
                    .toLowerCase(Locale.ROOT);
            if (!"all".equals(mode) && !"clone".equals(mode) && !"non_owner".equals(mode)) {
                mode = ModuleProps.DEFAULT_MODE;
            }
            return new Config(
                    getBooleanProp(ModuleProps.ENABLED, true),
                    mode,
                    parseIntSet(getProp(ModuleProps.USERS, "")),
                    getBooleanProp(ModuleProps.LOG, false));
        }
    }

    private static final class UserRecord {
        final int id;
        final boolean isClone;

        private UserRecord(int id, boolean isClone) {
            this.id = id;
            this.isClone = isClone;
        }
    }

    private static final class UserDiscoveryResult {
        final boolean available;
        final List<UserRecord> users;
        final String detail;

        private UserDiscoveryResult(boolean available, List<UserRecord> users, String detail) {
            this.available = available;
            this.users = users;
            this.detail = detail;
        }

        static UserDiscoveryResult available(List<UserRecord> users) {
            return new UserDiscoveryResult(true, users, "ok");
        }

        static UserDiscoveryResult unavailable(String detail) {
            return new UserDiscoveryResult(false, Collections.emptyList(), detail);
        }
    }

    private static final class UidRange {
        final int start;
        final int stop;

        private UidRange(int start, int stop) {
            this.start = start;
            this.stop = stop;
        }
    }
}
