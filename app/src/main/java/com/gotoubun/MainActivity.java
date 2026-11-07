package com.gotoubun;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String TAG = "CODM-Workspace";
    private static volatile boolean nativeLoaded = false;
    private static volatile boolean nativeLoadAttempted = false;

    private static synchronized void ensureNativeLoaded() {
        if (nativeLoadAttempted) return;
        nativeLoadAttempted = true;
        try {
            System.loadLibrary("nino");
            nativeLoaded = true;
        } catch (Throwable error) {
            nativeLoaded = false;
            Log.w(TAG, "Native library unavailable; continuing in safe fallback mode.", error);
        }
    }

    static {
        ensureNativeLoaded();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            // Lightweight fallback status UI; keeps app alive even when startup cannot complete.
            TextView tv = new TextView(this);
            tv.setPadding(32, 48, 32, 32);
            tv.setText("CODM Workspace\n\nStarting safely...\nOverlay/menu will retry on open/resume.\nIf overlay permission is required, grant it and reopen.");
            setContentView(tv);
        } catch (Throwable t) {
            Log.w(TAG, "Fallback content view failed.", t);
        }
        StartupState.setNativeLoaded(getApplicationContext(), nativeLoaded);
        safeInit("onCreate");
    }

    @Override
    protected void onResume() {
        super.onResume();
        safeInit("onResume");
    }

    private void safeInit(String reason) {
        try {
            if (!StartupState.shouldAttemptFromLifecycle(getApplicationContext(), reason)) {
                Log.i(TAG, "Startup deferred by persisted state/backoff (" + reason + "): " + StartupState.summary(getApplicationContext()));
                return;
            }
            Launcher.Init(this);
        } catch (Throwable error) {
            StartupState.markFailure(getApplicationContext(), StartupState.Phase.STARTUP_FAILED, error.getClass().getSimpleName());
            Log.w(TAG, "Startup init failed safely; app remains in fallback mode (" + reason + ")", error);
            try {
                Toast.makeText(getApplicationContext(), "Startup issue detected. Safe fallback active; retry on reopen/resume.", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        }
    }
}
