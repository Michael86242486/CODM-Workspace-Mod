package com.gotoubun;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
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
            Log.i(TAG, "Native library loaded.");
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
        safeInit("onCreate");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Retry only on resume/app-open path; Launcher/StartupGuard enforces backoff (no spam).
        safeInit("onResume");
    }

    private void safeInit(String reason) {
        try {
            if (!StartupGuard.canAttempt(this, "launcher_init")) {
                return;
            }
            Launcher.Init(this);
        } catch (Throwable error) {
            StartupGuard.markFailure(this, "launcher_init", error.getClass().getSimpleName());
            Log.w(TAG, "Startup init failed safely (" + reason + ")", error);
            try {
                Toast.makeText(getApplicationContext(), "Startup delayed; will retry safely when you reopen the app.", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        }
    }
}
