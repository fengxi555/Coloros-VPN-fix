package dev.gzq.coloroscloneproxy;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    static final String EXTRA_INITIAL_TAB = "dev.gzq.coloroscloneproxy.extra.INITIAL_TAB";
    static final String TAB_SETTINGS_VALUE = "settings";

    private static final String MODULE_VERSION = "2.5";
    private static final int REQUEST_EXPORT_LOG = 1001;
    private static final int TAB_HOME = 0;
    private static final int TAB_SETTINGS = 1;
    private static final long PAGE_ANIM_MS = 460L;

    private static final int BG_TOP = Color.rgb(250, 253, 252);
    private static final int BG_BOTTOM = Color.rgb(237, 247, 244);
    private static final int GLASS_TOP = Color.argb(232, 255, 255, 255);
    private static final int GLASS_BOTTOM = Color.argb(172, 255, 255, 255);
    private static final int GLASS_STROKE = Color.argb(206, 255, 255, 255);
    private static final int TEXT = Color.rgb(22, 50, 44);
    private static final int MUTED = Color.rgb(94, 119, 113);
    private static final int DARK_TEXT = Color.rgb(6, 89, 68);
    private static final int ACCENT = Color.rgb(78, 213, 147);
    private static final int WARN = Color.rgb(255, 198, 128);
    private static final int RED = Color.rgb(255, 145, 132);
    private static final int RED_SOFT = Color.argb(56, 255, 145, 132);

    private FrameLayout contentHost;
    private View currentPage;
    private LinearLayout homeNav;
    private LinearLayout settingsNav;
    private BlurNavLayout bottomNav;
    private TextView sizeView;
    private TextView syncView;
    private Switch loggingSwitch;
    private Switch hideLauncherSwitch;
    private int currentTab = TAB_HOME;
    private boolean updatingSwitches;
    private boolean pageAnimating;
    private boolean resumedOnce;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        currentTab = initialTab(getIntent());
        setContentView(buildRoot());
        showTab(currentTab, false);
        animateInitialPageIn();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        switchToTab(initialTab(intent), true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!resumedOnce) {
            resumedOnce = true;
            if (currentTab == TAB_SETTINGS) {
                syncAndRecord("resume");
                updateStateViews();
            }
            return;
        }
        if (currentTab == TAB_SETTINGS) {
            syncAndRecord("resume");
            updateStateViews();
        } else if (!pageAnimating) {
            showTab(TAB_HOME, false);
        }
    }

    private FrameLayout buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(background());

        contentHost = new FrameLayout(this);
        contentHost.setClipChildren(true);
        contentHost.setClipToPadding(true);
        root.addView(contentHost, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        bottomNav = buildBottomNav();
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        bottomParams.setMargins(dp(46), 0, dp(46), dp(8));
        root.addView(bottomNav, bottomParams);
        return root;
    }

    private void switchToTab(int targetTab, boolean animate) {
        if (targetTab == currentTab) {
            if (targetTab == TAB_SETTINGS) {
                updateStateViews();
            }
            return;
        }
        showTab(targetTab, animate);
    }

    private void showTab(int targetTab, boolean animate) {
        if (contentHost == null || pageAnimating) {
            return;
        }
        if (targetTab == TAB_SETTINGS) {
            syncAndRecord("open");
        }

        int oldTab = currentTab;
        View oldPage = currentPage;
        View nextPage = targetTab == TAB_HOME ? buildHomeContent() : buildSettingsContent();
        currentTab = targetTab;
        currentPage = nextPage;
        updateNavSelection();

        if (!animate || oldPage == null || contentHost.getWidth() <= 0) {
            contentHost.removeAllViews();
            contentHost.addView(nextPage, contentParams());
            return;
        }

        int width = contentHost.getWidth();
        int direction = targetTab > oldTab ? 1 : -1;
        pageAnimating = true;
        nextPage.setTranslationX(direction * width);
        nextPage.setAlpha(1f);
        contentHost.addView(nextPage, contentParams());

        AccelerateDecelerateInterpolator interpolator = new AccelerateDecelerateInterpolator();
        oldPage.animate()
                .translationX(-direction * width)
                .alpha(1f)
                .setDuration(PAGE_ANIM_MS)
                .setInterpolator(interpolator)
                .setUpdateListener(animation -> invalidateBottomNav())
                .start();
        nextPage.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(PAGE_ANIM_MS)
                .setInterpolator(interpolator)
                .setUpdateListener(animation -> invalidateBottomNav())
                .withEndAction(() -> {
                    contentHost.removeView(oldPage);
                    oldPage.setTranslationX(0f);
                    oldPage.setAlpha(1f);
                    pageAnimating = false;
                    invalidateBottomNav();
                })
                .start();
    }

    private FrameLayout.LayoutParams contentParams() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private int initialTab(Intent intent) {
        if (intent != null && TAB_SETTINGS_VALUE.equals(intent.getStringExtra(EXTRA_INITIAL_TAB))) {
            return TAB_SETTINGS;
        }
        return TAB_HOME;
    }

    private ScrollView buildHomeContent() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                invalidateBottomNav());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(44), dp(22), dp(92));
        scrollView.addView(root);

        TextView eyebrow = text("System VPN range", 13f, MUTED, Typeface.BOLD);
        eyebrow.setAllCaps(true);
        centerHeader(eyebrow);
        root.addView(eyebrow, matchWrapParams());

        TextView title = text("ColorOS VPN Fix", 34f, TEXT, Typeface.BOLD);
        centerHeader(title);
        title.setPadding(0, dp(8), 0, dp(8));
        root.addView(title, matchWrapParams());

        TextView subtitle = text("让主用户 VPN 覆盖 ColorOS 分身用户。", 16f, MUTED, Typeface.NORMAL);
        centerHeader(subtitle);
        subtitle.setPadding(0, 0, 0, dp(24));
        root.addView(subtitle, matchWrapParams());

        Status status = readStatus();
        root.addView(statusPanel(status), marginParams(0, 0, 0, dp(16)));
        root.addView(metricsRow(status), marginParams(0, 0, 0, dp(16)));
        root.addView(infoPanel(status), marginParams(0, 0, 0, dp(28)));
        return scrollView;
    }

    private ScrollView buildSettingsContent() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setClipToPadding(false);
        scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                invalidateBottomNav());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(44), dp(22), dp(92));
        scrollView.addView(root);

        TextView eyebrow = text("Module controls", 13f, MUTED, Typeface.BOLD);
        eyebrow.setAllCaps(true);
        centerHeader(eyebrow);
        root.addView(eyebrow, matchWrapParams());

        TextView title = text("设置", 34f, TEXT, Typeface.BOLD);
        centerHeader(title);
        title.setPadding(0, dp(8), 0, dp(8));
        root.addView(title, matchWrapParams());

        TextView subtitle = text("管理日志、桌面入口和诊断导出。", 16f, MUTED, Typeface.NORMAL);
        centerHeader(subtitle);
        subtitle.setPadding(0, 0, 0, dp(24));
        root.addView(subtitle, matchWrapParams());

        root.addView(launcherPanel(), marginParams(0, 0, 0, dp(16)));
        root.addView(logPanel(), marginParams(0, 0, 0, dp(28)));
        updateStateViews();
        return scrollView;
    }

    private LinearLayout statusPanel(Status status) {
        LinearLayout panel = glassPanel(dp(24));
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setMinimumHeight(dp(112));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER);
        panel.addView(top, matchWrapParams());

        TextView badge = text(status.active ? "ACTIVE" : "CHECK", 13f,
                status.active ? Color.WHITE : Color.rgb(84, 43, 14), Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(new RoundRectDrawable(status.active ? DARK_TEXT : WARN, dp(17)));
        LinearLayout.LayoutParams badgeParams = fixedParams(dp(86), dp(34));
        badgeParams.setMargins(0, 0, dp(18), 0);
        top.addView(badge, badgeParams);

        TextView state = text(status.active ? "已接管" : "未激活", 28f, TEXT, Typeface.BOLD);
        state.setGravity(Gravity.CENTER);
        top.addView(state, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    private LinearLayout metricsRow(Status status) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(metric("目标用户", defaultText(status.users, "none")), metricParams(true));
        row.addView(metric("扩展范围", defaultText(status.expandedRanges, "0")), metricParams(false));
        return row;
    }

    private LinearLayout metric(String label, String value) {
        LinearLayout panel = glassPanel(dp(18));
        panel.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(label, 13f, MUTED, Typeface.BOLD);
        TextView number = text(value, 22f, TEXT, Typeface.BOLD);
        number.setPadding(0, dp(8), 0, 0);
        panel.addView(title);
        panel.addView(number);
        return panel;
    }

    private LinearLayout infoPanel(Status status) {
        LinearLayout panel = glassPanel(dp(22));
        panel.setOrientation(LinearLayout.VERTICAL);
        addInfo(panel, "API 版本", defaultText(status.api, "等待系统框架加载"));
        addInfo(panel, "模块版本", MODULE_VERSION);
        addInfo(panel, "框架版本", defaultText(status.framework, "等待系统框架加载"));
        addInfo(panel, "系统版本", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        addInfo(panel, "设备", Build.MANUFACTURER + " " + Build.MODEL);
        return panel;
    }

    private LinearLayout launcherPanel() {
        LinearLayout panel = glassPanel(dp(22));
        panel.setOrientation(LinearLayout.VERTICAL);

        TextView title = text("桌面入口", 22f, TEXT, Typeface.BOLD);
        panel.addView(title);

        TextView hint = text("隐藏后不会影响模块启用，只会从桌面启动器移除图标。", 14f, MUTED, Typeface.NORMAL);
        hint.setPadding(0, dp(8), 0, dp(18));
        hint.setLineSpacing(dp(2), 1f);
        panel.addView(hint);

        LinearLayout row = settingRow("隐藏当前应用", "关闭桌面上的启动图标");
        hideLauncherSwitch = new Switch(this);
        hideLauncherSwitch.setOnCheckedChangeListener(this::onHideLauncherChanged);
        row.addView(hideLauncherSwitch);
        panel.addView(row);
        return panel;
    }

    private LinearLayout logPanel() {
        LinearLayout panel = glassPanel(dp(22));
        panel.setOrientation(LinearLayout.VERTICAL);

        TextView title = text("诊断日志", 22f, TEXT, Typeface.BOLD);
        panel.addView(title);

        syncView = text("", 14f, MUTED, Typeface.NORMAL);
        syncView.setPadding(0, dp(8), 0, dp(18));
        syncView.setLineSpacing(dp(2), 1f);
        panel.addView(syncView);

        LinearLayout switchRow = settingRow("记录诊断日志", "system_server 会直接写入应用私有日志");
        loggingSwitch = new Switch(this);
        loggingSwitch.setOnCheckedChangeListener(this::onLoggingChanged);
        switchRow.addView(loggingSwitch);
        panel.addView(switchRow);

        sizeView = text("", 16f, TEXT, Typeface.NORMAL);
        sizeView.setPadding(dp(16), dp(14), dp(16), dp(14));
        sizeView.setBackground(new RoundRectDrawable(Color.argb(138, 255, 255, 255), dp(18)));
        panel.addView(sizeView, marginParams(0, dp(16), 0, 0));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, dp(14), 0, 0);
        actions.addView(actionButton("导出日志", TEXT, Color.argb(168, 255, 255, 255),
                this::exportLog), actionButtonParams(true));
        actions.addView(actionButton("清除缓存", RED, RED_SOFT, () -> {
            if (CloneVpnLogProvider.clearLog(this)) {
                Toast.makeText(this, "日志已清除", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "日志清除失败", Toast.LENGTH_SHORT).show();
            }
            updateStateViews();
        }), actionButtonParams(false));
        panel.addView(actions);
        return panel;
    }

    private LinearLayout settingRow(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(title, 18f, TEXT, Typeface.BOLD);
        TextView detail = text(subtitle, 14f, MUTED, Typeface.NORMAL);
        detail.setPadding(0, dp(4), dp(12), 0);
        textCol.addView(label);
        textCol.addView(detail);
        row.addView(textCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private BlurNavLayout buildBottomNav() {
        BlurNavLayout bar = new BlurNavLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(5), dp(5), dp(5), dp(5));
        bar.setBlurTarget(contentHost);
        bar.setElevation(dp(16));

        homeNav = navItem("主页", TAB_HOME);
        settingsNav = navItem("设置", TAB_SETTINGS);
        bar.addView(homeNav, navParams());
        bar.addView(settingsNav, navParams());
        updateNavSelection();
        return bar;
    }

    private LinearLayout navItem(String label, int tab) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(3), 0, dp(3));

        TextView icon = text(tab == TAB_HOME ? "⌂" : "⚙", 20f, MUTED, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setIncludeFontPadding(false);
        item.addView(icon, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView text = text(label, 12f, MUTED, Typeface.NORMAL);
        text.setGravity(Gravity.CENTER);
        text.setIncludeFontPadding(false);
        item.addView(text, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        item.setOnClickListener(v -> switchToTab(tab, true));
        return item;
    }

    private void updateNavSelection() {
        updateNavItem(homeNav, currentTab == TAB_HOME);
        updateNavItem(settingsNav, currentTab == TAB_SETTINGS);
    }

    private void updateNavItem(LinearLayout item, boolean selected) {
        if (item == null) {
            return;
        }
        int color = selected ? Color.rgb(12, 41, 35) : Color.rgb(24, 32, 30);
        if (item.getChildCount() >= 2) {
            ((TextView) item.getChildAt(0)).setTextColor(color);
            ((TextView) item.getChildAt(1)).setTextColor(color);
            ((TextView) item.getChildAt(1)).setTypeface(Typeface.DEFAULT,
                    selected ? Typeface.BOLD : Typeface.NORMAL);
        }
        item.setBackground(selected
                ? new StrokeRoundRectDrawable(Color.rgb(231, 241, 255),
                        Color.rgb(255, 255, 255), dp(24), dp(1))
                : new RoundRectDrawable(Color.TRANSPARENT, dp(18)));
        item.setElevation(selected ? dp(2) : 0f);
    }

    private void invalidateBottomNav() {
        if (bottomNav != null) {
            bottomNav.invalidate();
        }
    }

    private TextView actionButton(String label, int textColor, int bgColor, Runnable action) {
        TextView button = text(label, 16f, textColor, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(new RoundRectDrawable(bgColor, dp(16)));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void onLoggingChanged(CompoundButton button, boolean enabled) {
        if (updatingSwitches) {
            return;
        }
        if (!enabled) {
            CloneVpnLogProvider.appendLocal(this, "log switch disabled");
        }
        boolean saved = CloneVpnLogProvider.setLoggingEnabled(this, enabled);
        if (enabled && saved) {
            CloneVpnLogProvider.appendLocal(this, "log switch enabled");
            syncAndRecord("enabled");
        }
        Toast.makeText(this,
                saved ? (enabled ? "日志已开启" : "日志已关闭") : "日志开关保存失败",
                Toast.LENGTH_SHORT).show();
        updateStateViews();
    }

    private void onHideLauncherChanged(CompoundButton button, boolean hidden) {
        if (updatingSwitches) {
            return;
        }
        int newState = hidden
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        getPackageManager().setComponentEnabledSetting(launcherComponent(), newState,
                PackageManager.DONT_KILL_APP);
        Toast.makeText(this,
                hidden ? "已从桌面隐藏" : "已显示到桌面",
                Toast.LENGTH_SHORT).show();
        updateStateViews();
    }

    private void exportLog() {
        syncAndRecord("export");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "clone-vpn-fix.log");
        startActivityForResult(intent, REQUEST_EXPORT_LOG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_LOG || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try {
            copyLogTo(uri);
            Toast.makeText(this, "日志已导出", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "日志导出失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void copyLogTo(Uri uri) throws Exception {
        syncAndRecord("copy");
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) {
                throw new IllegalStateException("openOutputStream returned null");
            }
            byte[] buffer = new byte[8192];
            for (File file : CloneVpnLogProvider.exportLogFiles(this)) {
                if (!file.isFile()) {
                    continue;
                }
                output.write(("\n===== " + file.getName() + " =====\n")
                        .getBytes(StandardCharsets.UTF_8));
                try (FileInputStream input = new FileInputStream(file)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private void syncAndRecord(String reason) {
        if (!CloneVpnLogProvider.isLoggingEnabled(this)) {
            return;
        }
        int privateSeq = CloneVpnLogProvider.lastPrivateSeq(this);
        CloneVpnLogProvider.appendLocal(this,
                "settings " + reason + " log sync privateSeq=" + privateSeq
                        + " size=" + CloneVpnLogProvider.logSize(this));
    }

    private void updateStateViews() {
        updatingSwitches = true;
        if (loggingSwitch != null) {
            loggingSwitch.setChecked(CloneVpnLogProvider.isLoggingEnabled(this));
        }
        if (hideLauncherSwitch != null) {
            hideLauncherSwitch.setChecked(isLauncherHidden());
        }
        updatingSwitches = false;

        if (sizeView != null) {
            int privateSeq = CloneVpnLogProvider.lastPrivateSeq(this);
            sizeView.setText("日志大小  " + formatSize(CloneVpnLogProvider.logSize(this))
                    + "\n诊断序号  " + (privateSeq < 0 ? "暂无" : String.valueOf(privateSeq)));
        }
        if (syncView != null) {
            syncView.setText(CloneVpnLogProvider.isLoggingEnabled(this)
                    ? "日志已开启。模块会直接写入应用私有文件，清除日志会同时清掉当前文件和备份文件。"
                    : "日志未开启。主页状态仍会更新，诊断细节不会持续记录。");
        }
    }

    private boolean isLauncherHidden() {
        int state = getPackageManager().getComponentEnabledSetting(launcherComponent());
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private ComponentName launcherComponent() {
        return new ComponentName(this, getPackageName() + ".LauncherActivity");
    }

    private void addInfo(LinearLayout root, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, dp(16));
        TextView title = text(label, 13f, MUTED, Typeface.BOLD);
        TextView detail = text(value, 17f, TEXT, Typeface.NORMAL);
        detail.setPadding(0, dp(4), 0, 0);
        row.addView(title);
        row.addView(detail);
        root.addView(row);
    }

    private Status readStatus() {
        Status status = new Status();
        status.bootId = readStatusValue(ModuleProps.SETTING_BOOT_ID, ModuleProps.PROP_BOOT_ID, "");
        String currentBootId = currentBootId();
        if (!status.bootId.isEmpty()
                && !currentBootId.isEmpty()
                && !status.bootId.equals(currentBootId)) {
            status.active = false;
            status.state = "inactive";
            status.detail = "未检测到本次开机的 system_server 状态，已忽略旧记录";
            status.framework = "等待系统框架加载";
            status.expandedRanges = "0";
            status.users = "none";
            return status;
        }

        status.active = "1".equals(readStatusValue(ModuleProps.SETTING_ACTIVE,
                ModuleProps.PROP_ACTIVE, "0"));
        status.state = readStatusValue(ModuleProps.SETTING_STATE, ModuleProps.PROP_STATE, "inactive");
        status.detail = readStatusValue(ModuleProps.SETTING_DETAIL, ModuleProps.PROP_DETAIL, "");
        status.api = readStatusValue(ModuleProps.SETTING_API, ModuleProps.PROP_API, "");
        status.framework = readStatusValue(ModuleProps.SETTING_FRAMEWORK, ModuleProps.PROP_FRAMEWORK, "");
        status.expandedRanges = readStatusValue(ModuleProps.SETTING_EXPANDED_RANGES,
                ModuleProps.PROP_EXPANDED_RANGES, "");
        status.users = readStatusValue(ModuleProps.SETTING_USERS, ModuleProps.PROP_USERS, "");
        return status;
    }

    private String readStatusValue(String settingKey, String propKey, String fallback) {
        String prop = readProp(propKey, "");
        if (!prop.isEmpty()) {
            return prop;
        }
        String setting = readSetting(settingKey, "");
        return setting.isEmpty() ? fallback : setting;
    }

    private String readSetting(String key, String fallback) {
        try {
            String value = Settings.Global.getString(getContentResolver(), key);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private String readProp(String key, String fallback) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Object value = systemProperties
                    .getMethod("get", String.class, String.class)
                    .invoke(null, key, fallback);
            return value instanceof String ? (String) value : fallback;
        } catch (Throwable ignored) {
            return readPropWithGetprop(key, fallback);
        }
    }

    private String readPropWithGetprop(String key, String fallback) {
        try {
            java.lang.Process process = new ProcessBuilder("getprop", key)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                process.waitFor();
                return line == null || line.trim().isEmpty() ? fallback : line.trim();
            }
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String currentBootId() {
        return readSmallFile(new File("/proc/sys/kernel/random/boot_id"), 128).trim();
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

    private LinearLayout glassPanel(int padding) {
        LinearLayout panel = new LinearLayout(this);
        panel.setPadding(padding, padding, padding, padding);
        panel.setBackground(new GlassPanelDrawable(GLASS_TOP, GLASS_BOTTOM,
                GLASS_STROKE, dp(24), dp(1)));
        panel.setElevation(dp(8));
        return panel;
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(BG_TOP);
        window.setNavigationBarColor(BG_BOTTOM);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private GradientDrawable background() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { BG_TOP, BG_BOTTOM });
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(true);
        return view;
    }

    private void centerHeader(TextView view) {
        view.setGravity(Gravity.CENTER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            view.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        }
    }

    private LinearLayout.LayoutParams marginParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private LinearLayout.LayoutParams matchWrapParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fixedParams(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private LinearLayout.LayoutParams metricParams(boolean left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (left) {
            params.setMargins(0, 0, dp(8), 0);
        } else {
            params.setMargins(dp(8), 0, 0, 0);
        }
        return params;
    }

    private LinearLayout.LayoutParams actionButtonParams(boolean left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1f);
        if (left) {
            params.setMargins(0, 0, dp(7), 0);
        } else {
            params.setMargins(dp(7), 0, 0, 0);
        }
        return params;
    }

    private LinearLayout.LayoutParams navParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private void animateInitialPageIn() {
        if (currentPage == null) {
            return;
        }
        currentPage.setAlpha(0f);
        currentPage.setTranslationY(dp(6));
        currentPage.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320L)
                .setInterpolator(new DecelerateInterpolator(1.35f))
                .start();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(java.util.Locale.US, "%.1f KB", kb);
        }
        return String.format(java.util.Locale.US, "%.2f MB", kb / 1024.0);
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class Status {
        boolean active;
        String state;
        String detail;
        String api;
        String framework;
        String expandedRanges;
        String users;
        String bootId;
    }
}
