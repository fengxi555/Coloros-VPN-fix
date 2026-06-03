package dev.gzq.coloroscloneproxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

final class SystemLogBridge {
    private SystemLogBridge() {
    }

    static int syncToPrivateLog(Context context) {
        if (!CloneVpnLogProvider.isLoggingEnabled(context)) {
            return 0;
        }

        String boot = readBoot(context);
        SharedPreferences prefs = context.getSharedPreferences(ModuleProps.LOG_PREFS,
                Context.MODE_PRIVATE);

        int currentSeq = readSeq(context);
        if (currentSeq < 0) {
            return 0;
        }

        String lastBoot = prefs.getString(ModuleProps.LOG_LAST_SYNC_BOOT, "");
        int lastSyncedSeq = prefs.getInt(ModuleProps.LOG_LAST_SYNC_SEQ, -1);
        if (!boot.equals(lastBoot)) {
            lastSyncedSeq = -1;
        }
        if (currentSeq <= lastSyncedSeq) {
            return 0;
        }

        int firstAvailableSeq = Math.max(0, currentSeq - ModuleProps.LOG_FALLBACK_SIZE + 1);
        int startSeq = Math.max(lastSyncedSeq + 1, firstAvailableSeq);
        int copied = 0;
        for (int seq = startSeq; seq <= currentSeq; seq++) {
            copied += appendSeq(context, boot, seq);
        }
        prefs.edit()
                .putString(ModuleProps.LOG_LAST_SYNC_BOOT, boot)
                .putInt(ModuleProps.LOG_LAST_SYNC_SEQ, currentSeq)
                .apply();
        return copied;
    }

    private static int appendSeq(Context context, String boot, int seq) {
        int slot = seq % ModuleProps.LOG_FALLBACK_SIZE;
        String line = readSlot(context, slot);
        if (line.isEmpty()) {
            return 0;
        }
        if (!boot.isEmpty() && !line.contains("boot=" + boot + " ")) {
            return 0;
        }
        if (!line.contains(" seq=" + seq + " ")) {
            return 0;
        }
        if (!CloneVpnLogProvider.appendLocal(context,
                "system_server fallback seq=" + seq + " slot=" + slot + " " + line)) {
            return 0;
        }
        return 1;
    }

    static void markCurrentSlotsSynced(Context context) {
        int currentSeq = readSeq(context);
        if (currentSeq < 0) {
            return;
        }
        String boot = readBoot(context);
        context.getSharedPreferences(ModuleProps.LOG_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(ModuleProps.LOG_LAST_SYNC_BOOT, boot)
                .putInt(ModuleProps.LOG_LAST_SYNC_SEQ, currentSeq)
                .apply();
    }

    static int readSeq(Context context) {
        try {
            String value = Settings.Global.getString(context.getContentResolver(),
                    ModuleProps.SETTING_LOG_SEQ);
            if (value != null && !value.trim().isEmpty()) {
                return Integer.parseInt(value.trim());
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    static String readBoot(Context context) {
        try {
            String value = Settings.Global.getString(context.getContentResolver(),
                    ModuleProps.SETTING_LOG_BOOT);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String readSlot(Context context, int slot) {
        try {
            String value = Settings.Global.getString(context.getContentResolver(),
                    ModuleProps.SETTING_LOG_PREFIX + slot);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }
}
