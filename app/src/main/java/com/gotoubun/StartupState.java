package com.gotoubun;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Persisted startup state for resilient, non-crashy retries.
 * Retries are only attempted from lifecycle entry points (app open/resume/service resume),
 * never from background loops.
 */
final class StartupState {
    static final String TAG = "CODM-Workspace";
    private static final String PREFS = "startup_state";

    enum Phase {
        NATIVE_MISSING,
        OVERLAY_PERMISSION_MISSING,
        SERVICE_START_FAILED,
        WINDOW_MANAGER_UNAVAILABLE,
        RUNTIME_PERMISSION_ISSUE,
        STARTUP_FAILED,
        READY
    }

    private static final String K_NATIVE_LOADED = "native_loaded";
    private static final String K_OVERLAY_PERMITTED = "overlay_permitted";
    private static final String K_SERVICE_STARTED = "service_started";
    private static final String K_STARTUP_FAILED_AT = "startup_failed_at";
    private static final String K_LAST_ERROR = "last_error";
    private static final String K_LAST_PHASE = "last_phase";
    private static final String K_FAIL_COUNT = "fail_count";
    private static final String K_NEXT_RETRY_AT = "next_retry_at";

    private static final long RECENT_FAILURE_WINDOW_MS = 2 * 60 * 1000L;
    private static final long BASE_BACKOFF_MS = 1500L;
    private static final long MAX_BACKOFF_MS = 30000L;

    private StartupState() {}

    static boolean isNativeLoaded(Context ctx) { return getBool(ctx, K_NATIVE_LOADED); }
    static boolean isOverlayPermitted(Context ctx) { return getBool(ctx, K_OVERLAY_PERMITTED); }
    static boolean isServiceStarted(Context ctx) { return getBool(ctx, K_SERVICE_STARTED); }

    static void setNativeLoaded(Context ctx, boolean v) { putBool(ctx, K_NATIVE_LOADED, v); }
    static void setOverlayPermitted(Context ctx, boolean v) { putBool(ctx, K_OVERLAY_PERMITTED, v); }
    static void setServiceStarted(Context ctx, boolean v) { putBool(ctx, K_SERVICE_STARTED, v); }

    static void markFailure(Context ctx, Phase phase, String detail) {
        if (ctx == null) return;
        try {
            Context app = ctx.getApplicationContext();
            long now = System.currentTimeMillis();
            SharedPreferences sp = sp(app);
            int fails = Math.min(8, sp.getInt(K_FAIL_COUNT, 0) + 1);
            long backoff = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L << Math.min(fails - 1, 4)));
            String err = (phase == null ? Phase.STARTUP_FAILED.name() : phase.name()) + (detail == null ? "" : ":" + detail);
            sp.edit()
                    .putLong(K_STARTUP_FAILED_AT, now)
                    .putString(K_LAST_ERROR, err)
                    .putString(K_LAST_PHASE, phase == null ? Phase.STARTUP_FAILED.name() : phase.name())
                    .putInt(K_FAIL_COUNT, fails)
                    .putLong(K_NEXT_RETRY_AT, now + backoff)
                    .putBoolean(K_SERVICE_STARTED, phase == Phase.READY)
                    .apply();
            Log.w(TAG, "Startup failure [" + phase + "] " + err + " (fails=" + fails + ", backoffMs=" + backoff + ")");
        } catch (Throwable t) {
            Log.w(TAG, "StartupState.markFailure failed", t);
        }
    }

    static void markReady(Context ctx) {
        if (ctx == null) return;
        try {
            Context app = ctx.getApplicationContext();
            sp(app).edit()
                    .putBoolean(K_SERVICE_STARTED, true)
                    .putBoolean(K_OVERLAY_PERMITTED, true)
                    .putLong(K_STARTUP_FAILED_AT, 0L)
                    .remove(K_LAST_ERROR)
                    .remove(K_LAST_PHASE)
                    .putInt(K_FAIL_COUNT, 0)
                    .putLong(K_NEXT_RETRY_AT, 0L)
                    .apply();
            Log.i(TAG, "Startup state marked READY.");
        } catch (Throwable t) {
            Log.w(TAG, "StartupState.markReady failed", t);
        }
    }

    /**
     * Returns true when a lifecycle-triggered startup attempt is allowed.
     * If a failure happened recently, allow one controlled retry path; if backoff is active, skip
     * and remain in safe fallback mode without crashing.
     */
    static boolean shouldAttemptFromLifecycle(Context ctx, String trigger) {
        if (ctx == null) return true;
        try {
            Context app = ctx.getApplicationContext();
            SharedPreferences sp = sp(app);
            long now = System.currentTimeMillis();
            long failedAt = sp.getLong(K_STARTUP_FAILED_AT, 0L);
            long nextRetryAt = sp.getLong(K_NEXT_RETRY_AT, 0L);
            if (failedAt <= 0L) return true;
            boolean recent = (now - failedAt) <= RECENT_FAILURE_WINDOW_MS;
            if (now < nextRetryAt) {
                Log.i(TAG, "Startup retry backoff active (" + trigger + "); remainingMs=" + (nextRetryAt - now) + ", phase=" + sp.getString(K_LAST_PHASE, "unknown"));
                return false;
            }
            if (recent) {
                Log.i(TAG, "Recent startup failure detected; allowing one lifecycle retry (" + trigger + ").");
                return true;
            }
            // Old failure state: allow lifecycle attempt and let outcome update state.
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "StartupState.shouldAttemptFromLifecycle failed open", t);
            return true;
        }
    }

    static String summary(Context ctx) {
        try {
            SharedPreferences sp = sp(ctx.getApplicationContext());
            return "native=" + sp.getBoolean(K_NATIVE_LOADED, false)
                    + ",overlay=" + sp.getBoolean(K_OVERLAY_PERMITTED, false)
                    + ",service=" + sp.getBoolean(K_SERVICE_STARTED, false)
                    + ",phase=" + sp.getString(K_LAST_PHASE, "none")
                    + ",error=" + sp.getString(K_LAST_ERROR, "none");
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    private static SharedPreferences sp(Context app) { return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static boolean getBool(Context ctx, String k) { try { return ctx != null && sp(ctx.getApplicationContext()).getBoolean(k, false); } catch (Throwable t) { return false; } }
    private static void putBool(Context ctx, String k, boolean v) { try { if (ctx != null) sp(ctx.getApplicationContext()).edit().putBoolean(k, v).apply(); } catch (Throwable ignored) {} }
}
