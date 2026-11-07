package com.gotoubun;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

public class Launcher {

    private static final String TAG = "CODM-Workspace";

    static {
        try {
            System.loadLibrary("nino");
        } catch (Throwable error) {
            Log.w(TAG, "Native library unavailable in Launcher; UI/service fallback remains available.", error);
        }
    }

    public static void Init(Object object) {
        if (!(object instanceof Context)) {
            Log.w(TAG, "Init called with non-Context object");
            return;
        }

        Context context = (Context) object;
        Activity activity = (object instanceof Activity) ? (Activity) object : null;
        Context app = context.getApplicationContext();

        if (!StartupGuard.canAttempt(app, "overlay_service")) {
            return;
        }

        // Connectivity guard: do not block local overlay UI, but avoid noisy startup work/log spam
        // when network-dependent paths fail; record state for diagnostics only.
        if (!isNetworkConnected(app)) {
            Log.i(TAG, "No active network detected; local overlay/service startup will still proceed safely.");
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                StartupGuard.markFailure(app, "overlay_service", "overlay_permission_missing");
                if (activity != null) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.getPackageName()));
                        if (intent.resolveActivity(context.getPackageManager()) != null) {
                            activity.startActivity(intent);
                        }
                        Toast.makeText(app, "Allow overlay permission, then reopen the app.", Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        Log.w(TAG, "Unable to open overlay settings safely.", t);
                    }
                }
                return;
            }

            try {
                nativeInit(context);
            } catch (Throwable t) {
                Log.w(TAG, "Native init failed; continuing with fallback UI.", t);
            }

            Intent serviceIntent = new Intent(context, Floating.class);
            try {
                context.startService(serviceIntent);
            } catch (Throwable e) {
                StartupGuard.markFailure(app, "overlay_service", e.getClass().getSimpleName());
                Log.w(TAG, "Failed to start Floating service safely.", e);
                if (activity != null) {
                    try {
                        Toast.makeText(app, "Service unavailable; app remains stable. Retry by reopening.", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                }
                return;
            }

            StartupGuard.markSuccess(app, "overlay_service");
            StartupGuard.markSuccess(app, "launcher_init");
        } catch (Throwable t) {
            StartupGuard.markFailure(app, "overlay_service", t.getClass().getSimpleName());
            Log.w(TAG, "Launcher init failed safely.", t);
        }
    }

    private static boolean isNetworkConnected(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            Log.w(TAG, "Connectivity check failed open.", t);
            return false;
        }
    }

    private static void nativeInit(Context mContext) {
        try {
            Init(mContext);
        } catch (UnsatisfiedLinkError ignored) {
            // Native entrypoint unavailable; fallback is intentional.
        }
    }

    private static native void Init(Context mContext);
}
