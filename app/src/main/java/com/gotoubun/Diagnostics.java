package com.gotoubun;

import android.content.Context;
import android.os.Build;
import java.util.Date;
import java.util.Locale;

/** Read-only diagnostics; does not change native/config behavior. */
final class Diagnostics {
    private static volatile String lastNote = "No developer events recorded yet.";

    private Diagnostics() {}

    static synchronized void note(String msg) {
        if (msg == null) return;
        lastNote = String.format(Locale.US, "%tT %s", new Date(), msg.replace('\n', ' '));
    }

    static synchronized String report(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Developer Diagnostics\n");
        sb.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append(" SDK=").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("pkg=").append(ctx == null ? "?" : ctx.getPackageName()).append('\n');
        sb.append("lastNote=").append(lastNote).append('\n');
        sb.append("\nWhy ESP/enemy detection may not work:\n");
        sb.append("- Native code must run in a process that has the target game's libil2cpp loaded and compatible metadata.\n");
        sb.append("- Overlay permission/service issues can prevent drawing; check Android overlay permission.\n");
        sb.append("- Game updates can change Il2Cpp class/field names/signatures used by this project.\n");
        sb.append("- If logcat shows UnsatisfiedLinkError/onCanvasDraw/onSendConfig errors, share them for a targeted fix.\n");
        sb.append("\nI can't help with injection, anti-cheat bypass, or gaining unfair advantage in online matches.\n");
        return sb.toString();
    }
}
