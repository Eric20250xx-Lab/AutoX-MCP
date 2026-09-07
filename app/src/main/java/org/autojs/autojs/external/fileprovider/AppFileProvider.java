package org.autojs.autojs.external.fileprovider;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.core.content.FileProvider;

import org.autojs.autoxjs.BuildConfig;

import java.io.File;

public class AppFileProvider extends FileProvider {

    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".fileprovider";
    private static final Uri STATE_URI = Uri.parse("content://" + AUTHORITY);
    private static final String METHOD_GET_RETRY_PENDING = "getMcpRetryPending";
    private static final String METHOD_SET_RETRY_PENDING = "setMcpRetryPending";
    private static final String STATE_PREFS = "mcp_retry_state";
    private static final String KEY_RETRY_PENDING = "foreground_start_pending";

    public static Uri getUriForFile(Context context, File file) {
        return FileProvider.getUriForFile(context, AUTHORITY, file);
    }

    public static boolean isMcpRetryPending(Context context) {
        Bundle result = context.getContentResolver().call(
                STATE_URI, METHOD_GET_RETRY_PENDING, null, null);
        return result != null && result.getBoolean(KEY_RETRY_PENDING, false);
    }

    public static void setMcpRetryPending(Context context, boolean pending) {
        Bundle extras = new Bundle();
        extras.putBoolean(KEY_RETRY_PENDING, pending);
        context.getContentResolver().call(STATE_URI, METHOD_SET_RETRY_PENDING, null, extras);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) {
            return super.call(method, arg, extras);
        }
        if (METHOD_GET_RETRY_PENDING.equals(method)) {
            Bundle result = new Bundle();
            result.putBoolean(KEY_RETRY_PENDING, context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_RETRY_PENDING, false));
            return result;
        }
        if (METHOD_SET_RETRY_PENDING.equals(method) && extras != null) {
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_RETRY_PENDING, extras.getBoolean(KEY_RETRY_PENDING, false)).commit();
            return new Bundle();
        }
        return super.call(method, arg, extras);
    }
}
