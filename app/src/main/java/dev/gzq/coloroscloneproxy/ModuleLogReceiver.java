package dev.gzq.coloroscloneproxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

public final class ModuleLogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !ModuleProps.LOG_ACTION_APPEND.equals(intent.getAction())) {
            return;
        }
        if (!isAllowedSender(context)) {
            return;
        }
        if (!CloneVpnLogProvider.isLoggingEnabled(context)) {
            return;
        }
        String message = intent.getStringExtra(ModuleProps.LOG_EXTRA_MESSAGE);
        int callerUid = intent.getIntExtra(ModuleProps.LOG_EXTRA_CALLER_UID, Process.SYSTEM_UID);
        CloneVpnLogProvider.appendModule(context, message, callerUid);
    }

    private boolean isAllowedSender(Context context) {
        int senderUid = sentFromUid();
        if (senderUid < 0) {
            return true;
        }
        int appUid = context.getApplicationInfo().uid;
        return senderUid == appUid || senderUid == Process.SYSTEM_UID || senderUid == 0;
    }

    private int sentFromUid() {
        try {
            Object value = BroadcastReceiver.class.getMethod("getSentFromUid").invoke(this);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
