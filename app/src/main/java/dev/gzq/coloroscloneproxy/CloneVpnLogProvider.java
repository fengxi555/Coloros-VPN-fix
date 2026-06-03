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
            result.putLong("size", logSize(context));
            result.putBoolean(ModuleProps.LOG_EXTRA_OK, true);
            return result;
        }

        if (ModuleProps.LOG_METHOD_APPEND.equals(method)) {
            boolean enabled = isLoggingEnabled(context);
            result.putBoolean(ModuleProps.LOG_EXTRA_ENABLED, enabled);
            result.putBoolean(ModuleProps.LOG_EXTRA_OK, enabled && appendLog(context,
                    Binder.getCallingUid(),
                    extras == null ? null : extras.getString(ModuleProps.LOG_EXTRA_MESSAGE)));
            result.putLong("size", logSize(context));
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
        return new File(context.getFilesDir(), ModuleProps.LOG_FILE_NAME);
    }

    static File backupLogFile(Context context) {
        return new File(context.getFilesDir(), LOG_FILE_BACKUP);
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
        return new File[] { current };
    }

    static long logSize(Context context) {
        File file = logFile(context);
        File backup = backupLogFile(context);
        return (file.isFile() ? file.length() : 0L)
                + (backup.isFile() ? backup.length() : 0L);
    }

    static boolean clearLog(Context context) {
        File file = logFile(context);
        boolean deleted = !file.exists() || file.delete();
        File backup = backupLogFile(context);
        if (backup.exists()) {
            deleted &= backup.delete();
        }
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
        return prefs(context).getBoolean(ModuleProps.LOG_ENABLED, false);
    }

    static boolean setLoggingEnabled(Context context, boolean enabled) {
        return prefs(context).edit().putBoolean(ModuleProps.LOG_ENABLED, enabled).commit();
    }

    static boolean appendLocal(Context context, String message) {
        return appendLog(context, Process.myUid(), message);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(ModuleProps.LOG_PREFS, Context.MODE_PRIVATE);
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
            return true;
        } catch (Throwable ignored) {
            return false;
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
