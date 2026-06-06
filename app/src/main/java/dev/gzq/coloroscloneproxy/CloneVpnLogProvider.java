package dev.gzq.coloroscloneproxy;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.UserManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CloneVpnLogProvider extends ContentProvider {
    private static final long MAX_LOG_BYTES = 4L * 1024L * 1024L;
    private static final String LOG_FILE_BACKUP = ModuleProps.LOG_FILE_NAME + ".bak";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceAllowedCaller();

        Bundle result = new Bundle();
        Context context = getContext();
        if (context == null) {
            result.putBoolean(ModuleProps.LOG_EXTRA_OK, false);
            return result;
        }

        if (ModuleProps.LOG_METHOD_IS_ENABLED.equals(method)) {
            result.putBoolean(ModuleProps.LOG_EXTRA_ENABLED, isLoggingEnabled(context));
            putLogMeta(context, result);
            result.putBoolean(ModuleProps.LOG_EXTRA_OK, true);
            return result;
        }

        if (ModuleProps.LOG_METHOD_APPEND.equals(method)) {
            boolean enabled = isLoggingEnabled(context);
            result.putBoolean(ModuleProps.LOG_EXTRA_ENABLED, enabled);
            result.putBoolean(ModuleProps.LOG_EXTRA_OK, enabled && appendLog(context,
                    Binder.getCallingUid(),
                    extras == null ? null : extras.getString(ModuleProps.LOG_EXTRA_MESSAGE)));
            putLogMeta(context, result);
            return result;
        }

        result.putBoolean(ModuleProps.LOG_EXTRA_OK, false);
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    static File logFile(Context context) {
        return new File(storageContext(context).getFilesDir(), ModuleProps.LOG_FILE_NAME);
    }

    static File backupLogFile(Context context) {
        return new File(storageContext(context).getFilesDir(), LOG_FILE_BACKUP);
    }

    static File[] exportLogFiles(Context context) {
        File backup = backupLogFile(context);
        File current = logFile(context);
        if (backup.isFile() && current.isFile()) {
            return new File[] { backup, current };
        }
        if (backup.isFile()) {
            return new File[] { backup };
        }
        File legacy = legacyLogFile(context);
        return !current.isFile() && legacy != null && legacy.isFile()
                ? new File[] { legacy } : new File[] { current };
    }

    static long logSize(Context context) {
        File file = logFile(context);
        File backup = backupLogFile(context);
        long size = (file.isFile() ? file.length() : 0L)
                + (backup.isFile() ? backup.length() : 0L);
        if (size <= 0L) {
            File legacy = legacyLogFile(context);
            File legacyBackup = legacyBackupLogFile(context);
            size += legacy != null && legacy.isFile() ? legacy.length() : 0L;
            size += legacyBackup != null && legacyBackup.isFile() ? legacyBackup.length() : 0L;
        }
        return size;
    }

    static boolean clearLog(Context context) {
        File file = logFile(context);
        boolean deleted = !file.exists() || file.delete();
        File backup = backupLogFile(context);
        if (backup.exists()) {
            deleted &= backup.delete();
        }
        File legacy = legacyLogFile(context);
        if (legacy != null && !samePath(file, legacy) && legacy.exists()) {
            deleted &= legacy.delete();
        }
        File legacyBackup = legacyBackupLogFile(context);
        if (legacyBackup != null && !samePath(backup, legacyBackup) && legacyBackup.exists()) {
            deleted &= legacyBackup.delete();
        }
        prefs(context).edit()
                .remove(ModuleProps.LOG_LAST_PRIVATE_SEQ)
                .remove(ModuleProps.LOG_LAST_PRIVATE_BOOT)
                .apply();
        clearLegacyLogMeta(context);
        return deleted;
    }

    private static void rotateLog(Context context) {
        File file = logFile(context);
        if (!file.exists()) {
            return;
        }
        File backup = backupLogFile(context);
        if (backup.exists()) {
            backup.delete();
        }
        file.renameTo(backup);
    }

    static boolean isLoggingEnabled(Context context) {
        SharedPreferences current = prefs(context);
        if (current.contains(ModuleProps.LOG_ENABLED)) {
            return current.getBoolean(ModuleProps.LOG_ENABLED, false);
        }
        if (legacyContains(context, ModuleProps.LOG_ENABLED)) {
            boolean enabled = legacyBoolean(context, ModuleProps.LOG_ENABLED, false);
            current.edit().putBoolean(ModuleProps.LOG_ENABLED, enabled).apply();
            return enabled;
        }
        return false;
    }

    static boolean setLoggingEnabled(Context context, boolean enabled) {
        boolean saved = prefs(context).edit().putBoolean(ModuleProps.LOG_ENABLED, enabled).commit();
        writeLegacyLoggingEnabled(context, enabled);
        return saved;
    }

    static boolean appendLocal(Context context, String message) {
        return appendLog(context, Process.myUid(), message);
    }

    static boolean appendModule(Context context, String message, int callerUid) {
        int uid = callerUid == 0 ? 0 : Process.SYSTEM_UID;
        return appendLog(context, uid, message);
    }

    static int lastPrivateSeq(Context context) {
        return prefs(context).getInt(ModuleProps.LOG_LAST_PRIVATE_SEQ,
                legacyInt(context, ModuleProps.LOG_LAST_PRIVATE_SEQ, -1));
    }

    static String lastPrivateBoot(Context context) {
        return prefs(context).getString(ModuleProps.LOG_LAST_PRIVATE_BOOT,
                legacyString(context, ModuleProps.LOG_LAST_PRIVATE_BOOT, ""));
    }

    private static SharedPreferences prefs(Context context) {
        return storageContext(context).getSharedPreferences(ModuleProps.LOG_PREFS,
                Context.MODE_PRIVATE);
    }

    private static SharedPreferences legacyPrefs(Context context) {
        return context.getSharedPreferences(ModuleProps.LOG_PREFS, Context.MODE_PRIVATE);
    }

    private static Context storageContext(Context context) {
        Context storage = context.createDeviceProtectedStorageContext();
        return storage == null ? context : storage;
    }

    private static File legacyLogFile(Context context) {
        if (!isUserUnlocked(context)) {
            return null;
        }
        return new File(context.getFilesDir(), ModuleProps.LOG_FILE_NAME);
    }

    private static File legacyBackupLogFile(Context context) {
        if (!isUserUnlocked(context)) {
            return null;
        }
        return new File(context.getFilesDir(), LOG_FILE_BACKUP);
    }

    private static boolean samePath(File left, File right) {
        if (left == null || right == null) {
            return false;
        }
        return left.getAbsolutePath().equals(right.getAbsolutePath());
    }

    private static boolean legacyContains(Context context, String key) {
        if (!isUserUnlocked(context)) {
            return false;
        }
        try {
            return legacyPrefs(context).contains(key);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean legacyBoolean(Context context, String key, boolean fallback) {
        if (!isUserUnlocked(context)) {
            return fallback;
        }
        try {
            return legacyPrefs(context).getBoolean(key, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int legacyInt(Context context, String key, int fallback) {
        if (!isUserUnlocked(context)) {
            return fallback;
        }
        try {
            return legacyPrefs(context).getInt(key, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String legacyString(Context context, String key, String fallback) {
        if (!isUserUnlocked(context)) {
            return fallback;
        }
        try {
            return legacyPrefs(context).getString(key, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void writeLegacyLoggingEnabled(Context context, boolean enabled) {
        if (!isUserUnlocked(context)) {
            return;
        }
        try {
            legacyPrefs(context).edit().putBoolean(ModuleProps.LOG_ENABLED, enabled).apply();
        } catch (Throwable ignored) {
            // Device-protected prefs are the source of truth now.
        }
    }

    private static void clearLegacyLogMeta(Context context) {
        if (!isUserUnlocked(context)) {
            return;
        }
        try {
            legacyPrefs(context).edit()
                    .remove(ModuleProps.LOG_LAST_PRIVATE_SEQ)
                    .remove(ModuleProps.LOG_LAST_PRIVATE_BOOT)
                    .apply();
        } catch (Throwable ignored) {
            // Legacy cleanup only.
        }
    }

    private static boolean isUserUnlocked(Context context) {
        try {
            UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
            return userManager == null || userManager.isUserUnlocked();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean appendLog(Context context, int callerUid, String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        File file = logFile(context);
        try {
            if (file.length() > MAX_LOG_BYTES) {
                rotateLog(context);
            }
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            String line = time + " uid=" + callerUid + " "
                    + message.replace('\r', ' ').replace('\n', ' ') + '\n';
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                writer.write(line);
            }
            if (callerUid == Process.SYSTEM_UID || callerUid == 0) {
                rememberPrivateModuleSeq(context, message);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void putLogMeta(Context context, Bundle result) {
        result.putLong(ModuleProps.LOG_EXTRA_SIZE, logSize(context));
        result.putInt(ModuleProps.LOG_EXTRA_LAST_PRIVATE_SEQ, lastPrivateSeq(context));
        result.putString(ModuleProps.LOG_EXTRA_LAST_PRIVATE_BOOT, lastPrivateBoot(context));
    }

    private static void rememberPrivateModuleSeq(Context context, String message) {
        ModuleSeq seq = parseModuleSeq(message);
        if (seq == null) {
            return;
        }
        prefs(context).edit()
                .putInt(ModuleProps.LOG_LAST_PRIVATE_SEQ, seq.seq)
                .putString(ModuleProps.LOG_LAST_PRIVATE_BOOT, seq.boot)
                .apply();
    }

    private static ModuleSeq parseModuleSeq(String message) {
        if (message == null) {
            return null;
        }
        int bootStart = message.indexOf("boot=");
        if (bootStart < 0) {
            return null;
        }
        int bootValueStart = bootStart + 5;
        int bootValueEnd = message.indexOf(' ', bootValueStart);
        if (bootValueEnd <= bootValueStart) {
            return null;
        }

        int seqStart = message.indexOf(" seq=", bootValueEnd);
        if (seqStart < 0) {
            return null;
        }
        int seqValueStart = seqStart + 5;
        int seqValueEnd = message.indexOf(' ', seqValueStart);
        if (seqValueEnd <= seqValueStart || message.indexOf(" t=", seqValueEnd) < 0) {
            return null;
        }
        try {
            return new ModuleSeq(message.substring(bootValueStart, bootValueEnd),
                    Integer.parseInt(message.substring(seqValueStart, seqValueEnd)));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class ModuleSeq {
        final String boot;
        final int seq;

        ModuleSeq(String boot, int seq) {
            this.boot = boot;
            this.seq = seq;
        }
    }

    private void enforceAllowedCaller() {
        Context context = getContext();
        int callerUid = Binder.getCallingUid();
        int appUid = context == null ? -1 : context.getApplicationInfo().uid;
        if (callerUid == appUid || callerUid == Process.SYSTEM_UID || callerUid == 0) {
            return;
        }
        throw new SecurityException("caller is not allowed to access Clone VPN Fix logs");
    }
}
