package com.gotoubun;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Lightweight startup/connectivity-style retry guard: limits retry attempts with
 * exponential backoff. Retries are intended to occur on next app open/service resume,
 * not by background loops.
 */
final class StartupGuard {
    private static final String TAG = "CODM-Workspace";
    private static final String PREFS = "startup_guard";
    private static final String KEY_FAIL_COUNT = "fail_count";
    private static final String KEY_NEXT_RETRY_AT = "next_retry_at";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final long BASE_MS = 1500L;
    private static final long MAX_MS = 30000L;
    private static final int MAX_TRACKED_FAILURES = 8;

    private StartupGuard() {}

    static boolean canAttempt(Context ctx, String op) {
        if (ctx == null) return true;
        try {
            long now = System.currentTimeMillis();
            long next = prefs(ctx).getLong(key(op, KEY_NEXT_RETRY_AT), 0L);
            if (now < next) {
                Log.i(TAG, "StartupGuard backoff active for " + op + " (" + (next - now) + "ms remaining)");
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "StartupGuard check failed open for " + op, t);
            return true;
        }
    }

    static void markSuccess(Context ctx, String op) {
        if (ctx == null) return;
        try {
            prefs(ctx).edit()
                    .remove(key(op, KEY_FAIL_COUNT))
                    .remove(key(op, KEY_NEXT_RETRY_AT))
                    .remove(key(op, KEY_LAST_ERROR))
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "StartupGuard clear failed for " + op, t);
        }
    }

    static void markFailure(Context ctx, String op, String reason) {
        if (ctx == null) return;
        try {
            SharedPreferences sp = prefs(ctx);
            int fails = Math.min(MAX_TRACKED_FAILURES, sp.getInt(key(op, KEY_FAIL_COUNT), 0) + 1);
            long delay = Math.min(MAX_MS, BASE_MS * (1L << Math.min(fails - 1, 4)));
            long next = System.currentTimeMillis() + delay;
            sp.edit()
                    .putInt(key(op, KEY_FAIL_COUNT), fails)
                    .putLong(key(op, KEY_NEXT_RETRY_AT), next)
                    .putString(key(op, KEY_LAST_ERROR), reason == null ? "unknown" : reason)
                    .apply();
            Log.w(TAG, "StartupGuard failure for " + op + " (attempt=" + fails + ", nextRetryMs=" + delay + "): " + reason);
        } catch (Throwable t) {
            Log.w(TAG, "StartupGuard record failed for " + op, t);
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String op, String suffix) {
        return (op == null ? "op" : op) + "." + suffix;
    }
}
