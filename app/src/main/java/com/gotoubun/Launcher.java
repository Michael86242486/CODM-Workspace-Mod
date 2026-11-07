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
    private static volatile boolean nativeLoaded = false;
    private static volatile boolean nativeLoadAttempted = false;
    private static volatile boolean serviceStartRequested = false;

    private static synchronized void ensureNativeLoaded() {
        if (nativeLoadAttempted) return;
        nativeLoadAttempted = true;
        try {
            System.loadLibrary("nino");
            nativeLoaded = true;
        } catch (Throwable error) {
            nativeLoaded = false;
            Log.w(TAG, "Native library unavailable in Launcher; UI/service fallback remains available.", error);
        }
    }

    static {
        ensureNativeLoaded();
    }

    public static void Init(Object object) {
        if (!(object instanceof Context)) {
            Log.w(TAG, "Init called with non-Context object");
            return;
        }

        Context context = (Context) object;
        Activity activity = (object instanceof Activity) ? (Activity) object : null;
        Context app = context.getApplicationContext();
        StartupState.setNativeLoaded(app, nativeLoaded);

        if (!StartupState.shouldAttemptFromLifecycle(app, "Launcher.Init")) {
            return;
        }

        // Connectivity is diagnostic only; local overlay/service startup must remain usable offline.
        if (!isNetworkConnected(app)) {
            Log.i(TAG, "No active network detected; proceeding with local safe startup.");
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean overlayOk = Settings.canDrawOverlays(context);
                StartupState.setOverlayPermitted(app, overlayOk);
                if (!overlayOk) {
                    StartupState.markFailure(app, StartupState.Phase.OVERLAY_PERMISSION_MISSING, "overlay_permission_missing");
                    if (activity != null) {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + context.getPackageName()));
                            if (intent.resolveActivity(context.getPackageManager()) != null) {
                                activity.startActivity(intent);
                            }
                            Toast.makeText(app, "Allow overlay permission, then reopen/resume the app.", Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            Log.w(TAG, "Unable to open overlay settings safely.", t);
                        }
                    }
                    return; // keep app alive; retry on resume
                }
            } else {
                StartupState.setOverlayPermitted(app, true);
            }

            try {
                nativeInit(context);
            } catch (Throwable t) {
                StartupState.markFailure(app, StartupState.Phase.NATIVE_MISSING, t.getClass().getSimpleName());
                Log.w(TAG, "Native init failed; continuing with fallback UI.", t);
            }

            // Idempotent start request: avoid duplicate start churn from repeated lifecycle events.
            if (serviceStartRequested && StartupState.isServiceStarted(app)) {
                Log.i(TAG, "Service already started/requested; skipping duplicate start.");
                return;
            }

            Intent serviceIntent = new Intent(context, Floating.class);
            try {
                context.startService(serviceIntent);
                serviceStartRequested = true;
                StartupState.setServiceStarted(app, true);
                StartupState.markReady(app);
            } catch (Throwable e) {
                serviceStartRequested = false;
                StartupState.setServiceStarted(app, false);
                StartupState.markFailure(app, StartupState.Phase.SERVICE_START_FAILED, e.getClass().getSimpleName());
                Log.w(TAG, "Failed to start Floating service safely; app remains alive for retry.", e);
                try {
                    Toast.makeText(app, "Service start delayed; safe fallback active. Retry on reopen/resume.", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
                return; // do not finish activity
            }
        } catch (Throwable t) {
            StartupState.markFailure(app, StartupState.Phase.STARTUP_FAILED, t.getClass().getSimpleName());
            Log.w(TAG, "Launcher init failed safely; fallback mode preserved.", t);
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
        } catch (Throwable t) {
            Log.w(TAG, "Native init threw; fallback continues.", t);
        }
    }

    private static native void Init(Context mContext);
}
