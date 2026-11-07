package com.gotoubun;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

public class Launcher {

    private static final String TAG = "Launcher";

    static {
        try {
            System.loadLibrary("nino");
        } catch (UnsatisfiedLinkError error) {
            Log.e(TAG, "Unable to load nino library; app will continue without native hooks.", error);
        }
    }

    public static void Init(Object object) {
        if (!(object instanceof Context)) {
            Log.w(TAG, "Init called with non-Context object: " + object);
            return;
        }

        Context context = (Context) object;
        Activity activity = (object instanceof Activity) ? (Activity) object : null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            if (activity != null) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.getPackageName()));
                if (intent.resolveActivity(context.getPackageManager()) != null) {
                    activity.startActivity(intent);
                }
            }
            return;
        }

        Intent serviceIntent = new Intent(context, Floating.class);
        try {
            context.startService(serviceIntent);
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, "Failed to start Floating service", e);
            if (activity != null) {
                activity.finish();
            }
        }
    }

    private static native void Init(Context mContext);
}
