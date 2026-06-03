package dev.gzq.coloroscloneproxy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class LogSettingsActivity extends Activity {
    private static final int REQUEST_EXPORT_LOG = 1001;
    private static final int BLUE = Color.rgb(14, 94, 214);
    private static final int BG = Color.rgb(248, 247, 255);
    private static final int TEXT = Color.rgb(33, 36, 48);
    private static final int MUTED = Color.rgb(83, 86, 103);
    private static final int STROKE = Color.rgb(198, 200, 218);
    private static final int SOFT_BLUE = Color.rgb(232, 240, 255);
    private static final int SOFT_RED = Color.rgb(255, 238, 238);
    private static final int RED = Color.rgb(176, 64, 64);

    private TextView sizeView;
    private TextView syncView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        setContentView(buildRoot());
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncAndRecord("resume");
        updateSize();
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
        root.setPadding(dp(22), dp(28), dp(22), dp(24));
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("诊断日志");
        title.setTextColor(TEXT);
        title.setTextSize(34f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(34), 0, dp(8));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("记录 system_server Hook、用户扫描和 VPN UID range 扩展结果。");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(16f);
        subtitle.setLineSpacing(dp(2), 1.0f);
        subtitle.setPadding(0, 0, 0, dp(22));
        root.addView(subtitle);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(22), dp(22), dp(22), dp(22));
        hero.setBackground(new RoundRectDrawable(BLUE, dp(22)));
        root.addView(hero, cardParams(dp(18)));

        TextView heroTitle = new TextView(this);
        heroTitle.setText("系统侧日志桥");
        heroTitle.setTextColor(Color.WHITE);
        heroTitle.setTextSize(22f);
        heroTitle.setTypeface(Typeface.DEFAULT_BOLD);
        hero.addView(heroTitle);

        syncView = new TextView(this);
        syncView.setTextColor(Color.rgb(230, 238, 255));
        syncView.setTextSize(15f);
        syncView.setLineSpacing(dp(2), 1.0f);
        syncView.setPadding(0, dp(10), 0, 0);
        hero.addView(syncView);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        card.setBackground(new StrokeRoundRectDrawable(Color.WHITE, STROKE, dp(20), dp(1)));
        root.addView(card, cardParams(0));

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(0, 0, 0, dp(18));
        card.addView(switchRow);

        LinearLayout switchText = new LinearLayout(this);
        switchText.setOrientation(LinearLayout.VERTICAL);
        switchRow.addView(switchText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView switchTitle = new TextView(this);
        switchTitle.setText("开启日志");
        switchTitle.setTextColor(TEXT);
        switchTitle.setTextSize(20f);
        switchTitle.setTypeface(Typeface.DEFAULT_BOLD);
        switchText.addView(switchTitle);

        TextView switchHint = new TextView(this);
        switchHint.setText("开启后优先写入私有日志文件，系统侧只作有限 fallback。");
        switchHint.setTextColor(MUTED);
        switchHint.setTextSize(14f);
        switchHint.setPadding(0, dp(4), dp(12), 0);
        switchText.addView(switchHint);

        Switch logging = new Switch(this);
        logging.setChecked(CloneVpnLogProvider.isLoggingEnabled(this));
        logging.setOnCheckedChangeListener(this::onLoggingChanged);
        switchRow.addView(logging);

        sizeView = new TextView(this);
        sizeView.setTextColor(TEXT);
        sizeView.setTextSize(18f);
        sizeView.setBackground(new RoundRectDrawable(SOFT_BLUE, dp(16)));
        sizeView.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.addView(sizeView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, dp(14), 0, dp(4));
        card.addView(actions);

        actions.addView(actionButton("导出日志", Color.WHITE, BLUE, () -> exportLog()),
                actionButtonParams(true));
        actions.addView(actionButton("清除缓存", RED, SOFT_RED, () -> {
            if (CloneVpnLogProvider.clearLog(this)) {
                SystemLogBridge.markCurrentSlotsSynced(this);
                Toast.makeText(this, "日志已清除", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "日志清除失败", Toast.LENGTH_SHORT).show();
            }
            updateSize();
        }), actionButtonParams(false));

        TextView hint = new TextView(this);
        hint.setText("日志默认关闭。VPN 建立时会记录用户发现来源、VPN 配置模式、注入策略、注入后是否重叠、以及系统最终生效的 UID range，用于定位分身断网原因。");
        hint.setTextColor(MUTED);
        hint.setTextSize(16f);
        hint.setLineSpacing(dp(2), 1.0f);
        hint.setPadding(0, dp(20), 0, 0);
        card.addView(hint);

        updateSize();
        return scrollView;
    }

    private void onLoggingChanged(CompoundButton button, boolean enabled) {
        boolean saved = CloneVpnLogProvider.setLoggingEnabled(this, enabled);
        if (enabled && saved) {
            CloneVpnLogProvider.appendLocal(this, "log switch enabled; local file write test");
            syncAndRecord("enabled");
        }
        Toast.makeText(this,
                saved ? (enabled ? "日志已开启" : "日志已关闭") : "日志开关保存失败",
                Toast.LENGTH_SHORT).show();
        updateSize();
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
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
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

    private void updateSize() {
        if (sizeView != null) {
            int seq = SystemLogBridge.readSeq(this);
            sizeView.setText("当前日志大小\n" + formatSize(CloneVpnLogProvider.logSize(this))
                    + "\n系统日志序号\n" + (seq < 0 ? "暂无" : String.valueOf(seq)));
        }
        if (syncView != null) {
            syncView.setText(CloneVpnLogProvider.isLoggingEnabled(this)
                    ? "日志已开启。优先写入私有日志文件，必要时从系统侧 fallback 缓冲同步。"
                    : "日志未开启。system_server 不再写入系统侧日志缓冲。");
        }
    }

    private int syncSystemLogs() {
        return SystemLogBridge.syncToPrivateLog(this);
    }

    private void syncAndRecord(String reason) {
        if (!CloneVpnLogProvider.isLoggingEnabled(this)) {
            return;
        }
        int seq = SystemLogBridge.readSeq(this);
        int copied = syncSystemLogs();
        if (copied > 0 || CloneVpnLogProvider.logSize(this) == 0L) {
            CloneVpnLogProvider.appendLocal(this,
                    "log page " + reason + " enabled seq=" + seq + " copied=" + copied);
        }
    }

    private LinearLayout buildBottomNav() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(14), dp(10), dp(14), dp(12));
        bar.setBackgroundColor(Color.WHITE);
        bar.setElevation(dp(10));

        bar.addView(navItem("主页", false, this::openHomeTab), navParams());
        bar.addView(navItem("日志", true, null), navParams());
        return bar;
    }

    private void openHomeTab() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
    }

    private TextView navItem(String text, boolean selected, Runnable action) {
        TextView item = new TextView(this);
        item.setText(text);
        item.setGravity(Gravity.CENTER);
        item.setTextSize(16f);
        item.setTypeface(Typeface.DEFAULT_BOLD);
        item.setTextColor(selected ? BLUE : MUTED);
        item.setBackground(selected
                ? new RoundRectDrawable(SOFT_BLUE, dp(18))
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

    private LinearLayout.LayoutParams cardParams(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, bottomMargin);
        return params;
    }

    private TextView actionButton(String text, int textColor, int bgColor, Runnable action) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(textColor);
        button.setTextSize(16f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(new RoundRectDrawable(bgColor, dp(14)));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams actionButtonParams(boolean left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1f);
        if (left) {
            params.setMargins(0, 0, dp(6), 0);
        } else {
            params.setMargins(dp(6), 0, 0, 0);
        }
        return params;
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
