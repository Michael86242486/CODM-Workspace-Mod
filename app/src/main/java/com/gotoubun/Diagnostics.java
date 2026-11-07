package com.gotoubun;

import android.content.Context;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/** In-app diagnostics for developers; stores recent non-sensitive runtime events. */
final class Diagnostics {
    private static final int MAX = 80;
    private static final Deque<String> EVENTS = new ArrayDeque<>();
    private static volatile boolean nativeLoadOk = false;
    private static volatile boolean nativeLoadTried = false;
    private static volatile String nativeLoadError = "none";
    private static volatile long lastCanvasDrawOkMs = 0L;
    private static volatile long lastCanvasDrawErrMs = 0L;
    private static volatile String lastCanvasDrawErr = "none";
    private static volatile long lastConfigSentMs = 0L;
    private static volatile String lastConfigErr = "none";
    private static volatile String lastActionError = "none";

    private Diagnostics() {}

    static synchronized void event(String msg) {
        if (msg == null) return;
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        EVENTS.addFirst(ts + " " + msg);
        while (EVENTS.size() > MAX) EVENTS.removeLast();
    }

    static void nativeLoad(boolean ok, String err) {
        nativeLoadTried = true; nativeLoadOk = ok; nativeLoadError = err == null ? (ok ? "ok" : "failed") : err;
        event("Native load " + (ok ? "ok" : "failed") + (ok ? "" : ": " + nativeLoadError));
    }
    static void canvasDrawOk() { lastCanvasDrawOkMs = System.currentTimeMillis(); }
    static void canvasDrawError(String err) { lastCanvasDrawErrMs = System.currentTimeMillis(); lastCanvasDrawErr = safe(err); event("onCanvasDraw error: " + lastCanvasDrawErr); }
    static void configSent(String key) { lastConfigSentMs = System.currentTimeMillis(); event("Config sent: " + safe(key)); }
    static void configError(String err) { lastConfigErr = safe(err); event("Config send error: " + lastConfigErr); }
    static void actionError(String where, Throwable t) { lastActionError = safe(where) + ": " + safe(t == null ? "unknown" : t.getClass().getSimpleName()); event("Action error " + lastActionError); }

    static synchronized String report(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Developer Diagnostics\n");
        sb.append("device=").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append(" SDK=").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("nativeTried=").append(nativeLoadTried).append(" nativeOk=").append(nativeLoadOk).append(" nativeErr=").append(safe(nativeLoadError)).append("\n");
        sb.append("lastCanvasOkMsAgo=").append(ago(lastCanvasDrawOkMs)).append(" lastCanvasErr=").append(safe(lastCanvasDrawErr)).append(" ago=").append(ago(lastCanvasDrawErrMs)).append("\n");
        sb.append("lastConfigSentAgo=").append(ago(lastConfigSentMs)).append(" lastConfigErr=").append(safe(lastConfigErr)).append("\n");
        sb.append("lastActionErr=").append(safe(lastActionError)).append("\n");
        try { if (ctx != null) sb.append("startup=").append(StartupState.summary(ctx.getApplicationContext())).append("\n"); } catch (Throwable ignored) {}
        sb.append("\nHints:\n");
        if (nativeLoadTried && !nativeLoadOk) sb.append("- Native library 'nino' failed to load in this process. ESP/aim native features cannot run.\n");
        if (lastCanvasDrawErrMs > 0 && !"none".equals(lastCanvasDrawErr)) sb.append("- onCanvasDraw threw; check process context, permissions, and native ABI/library packaging.\n");
        if (!"none".equals(lastConfigErr)) sb.append("- Config could not be sent to native; menu toggles may not affect native features.\n");
        sb.append("- Enemy detection depends on the target game process/libil2cpp context and compatible metadata/signatures.\n");
        sb.append("- This overlay app cannot read another app's game memory unless loaded into that process; injection/bypass help is not provided.\n");
        sb.append("\nRecent events:\n");
        int n = 0;
        for (String e : EVENTS) { sb.append("- ").append(e).append("\n"); if (++n >= 25) break; }
        if (n == 0) sb.append("- no events yet\n");
        return sb.toString();
    }

    private static String ago(long ms) { return ms <= 0 ? "never" : String.valueOf(Math.max(0, (System.currentTimeMillis() - ms) / 1000)) + "s"; }
    private static String safe(String s) {
        if (s == null) return "none";
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
