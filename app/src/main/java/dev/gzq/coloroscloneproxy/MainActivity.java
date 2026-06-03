package dev.gzq.coloroscloneproxy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String MODULE_VERSION = "2.3.0";
    private static final int BLUE = Color.rgb(14, 94, 214);
    private static final int BG = Color.rgb(248, 247, 255);
    private static final int TEXT = Color.rgb(33, 36, 48);
    private static final int MUTED = Color.rgb(83, 86, 103);
    private static final int STROKE = Color.rgb(198, 200, 218);
    private static final int NAV_SELECTED = Color.rgb(232, 240, 255);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        setContentView(buildRoot());
    }

    @Override
    protected void onResume() {
        super.onResume();
        setContentView(buildRoot());
    }

    private LinearLayout buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.addView(buildContent(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildBottomNav());
        return root;
    }

    private ScrollView buildContent() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(42), dp(22), dp(28));
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("ColorOS VPN Fix");
        title.setTextColor(TEXT);
        title.setTextSize(34f);
        title.setPadding(0, dp(40), 0, dp(28));
        root.addView(title);

        Status status = readStatus();
        root.addView(statusCard(status));
        root.addView(infoCard(status));
        return scrollView;
    }

    private LinearLayout buildBottomNav() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(14), dp(10), dp(14), dp(12));
        bar.setBackgroundColor(Color.WHITE);
        bar.setElevation(dp(10));

        bar.addView(navItem("主页", true, null), navParams());
        bar.addView(navItem("日志", false, this::openLogTab), navParams());
        return bar;
    }

    private void openLogTab() {
        startActivity(new Intent(this, LogSettingsActivity.class));
        overridePendingTransition(0, 0);
    }

    private TextView navItem(String text, boolean selected, Runnable action) {
        TextView item = new TextView(this);
        item.setText(text);
        item.setGravity(Gravity.CENTER);
        item.setTextSize(16f);
        item.setTypeface(Typeface.DEFAULT_BOLD);
        item.setTextColor(selected ? BLUE : MUTED);
        item.setBackground(selected
                ? new RoundRectDrawable(NAV_SELECTED, dp(18))
                : new RoundRectDrawable(Color.TRANSPARENT, dp(18)));
        if (action != null) {
            item.setOnClickListener(v -> action.run());
        }
        return item;
    }

    private LinearLayout.LayoutParams navParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private LinearLayout statusCard(Status status) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(24), dp(26), dp(24), dp(26));
        card.setBackground(new RoundRectDrawable(status.active ? BLUE : Color.rgb(116, 122, 143), dp(16)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(20));
        card.setLayoutParams(params);

        TextView icon = new TextView(this);
        icon.setText(status.active ? "✓" : "!");
        icon.setTextColor(BLUE);
        icon.setTextSize(28f);
        icon.setGravity(Gravity.CENTER);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setBackground(new RoundRectDrawable(Color.WHITE, dp(24)));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        iconParams.setMargins(0, 0, dp(22), 0);
        card.addView(icon, iconParams);

        TextView text = new TextView(this);
        text.setText((status.active ? "已激活" : "未激活") + "\n" + MODULE_VERSION);
        text.setTextColor(Color.WHITE);
        text.setTextSize(20f);
        text.setLineSpacing(dp(2), 1.0f);
        card.addView(text);
        return card;
    }

    private LinearLayout infoCard(Status status) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(26), dp(28), dp(26), dp(28));
        card.setBackground(new StrokeRoundRectDrawable(Color.TRANSPARENT, STROKE, dp(16), dp(1)));

        addInfo(card, "API 版本", defaultText(status.api, "101"));
        addInfo(card, "模块版本", MODULE_VERSION);
        addInfo(card, "框架版本", defaultText(status.framework, "等待系统框架加载"));
        addInfo(card, "Hook 状态", status.active ? defaultText(status.detail, "已接管 VPN UID 范围") : "未检测到 system_server 状态，请确认系统框架 system 已勾选并查看 Vector 日志");
        addInfo(card, "目标用户", defaultText(status.users, "none"));
        addInfo(card, "扩展范围", defaultText(status.expandedRanges, "0"));
        addInfo(card, "系统版本", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        addInfo(card, "设备", Build.MANUFACTURER + " " + Build.MODEL);
        addInfo(card, "推荐作用域", "系统框架 system");
        addInfo(card, "默认行为", "重启后让主用户 VPN 覆盖 ColorOS 分身用户");
        return card;
    }

    private void addInfo(LinearLayout root, String label, String value) {
        TextView view = new TextView(this);
        view.setText(label + "\n" + value);
        view.setTextColor(TEXT);
        view.setTextSize(18f);
        view.setLineSpacing(dp(2), 1.0f);
        view.setPadding(0, 0, 0, dp(24));
        root.addView(view);
    }

    private Status readStatus() {
        Status status = new Status();
        status.active = "1".equals(readStatusValue(ModuleProps.SETTING_ACTIVE, ModuleProps.PROP_ACTIVE, "0"));
        status.state = readStatusValue(ModuleProps.SETTING_STATE, ModuleProps.PROP_STATE, "inactive");
        status.detail = readStatusValue(ModuleProps.SETTING_DETAIL, ModuleProps.PROP_DETAIL, "");
        status.api = readStatusValue(ModuleProps.SETTING_API, ModuleProps.PROP_API, "");
        status.framework = readStatusValue(ModuleProps.SETTING_FRAMEWORK, ModuleProps.PROP_FRAMEWORK, "");
        status.expandedRanges = readStatusValue(ModuleProps.SETTING_EXPANDED_RANGES, ModuleProps.PROP_EXPANDED_RANGES, "");
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
    }
}
