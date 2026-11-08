package com.gotoubun;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

public class SandboxCore extends Service {

    private static final String TAG = "SandboxCore";

    // CODM inline hooks trigger - called when virtual sandbox boots
    public static native boolean sandboxBootTrigger();

    // VA++ native IO redirection methods
    public static native void nativeEnableIORedirect(String selfSoPath, int apiLevel, int previewApiLevel);
    public static native void nativeIOWhitelist(String path);
    public static native void nativeIOForbid(String path);
    public static native void nativeIORedirect(String origPath, String newPath);
    public static native String nativeGetRedirectedPath(String origPath);
    public static native String nativeReverseRedirectedPath(String redirectedPath);

    static {
        System.loadLibrary("va++");
        System.loadLibrary("nino");
    }

    private final IBinder mBinder = new LocalBinder();
    private VActivityManager mVActivityManager;
    private VPackageManager mVPackageManager;
    private ZygoteProxy mZygoteProxy;

    public class LocalBinder extends Binder {
        public SandboxCore getService() {
            return SandboxCore.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        initializeSandbox();
    }

    private void initializeSandbox() {
        try {
            mVActivityManager = new VActivityManager(this);
            mVPackageManager = new VPackageManager(this);
            mZygoteProxy = new ZygoteProxy(this);

            // Trigger CODM inline hooks - background execution thread launches
            // automatically when internal virtual sandbox boots up. This gives
            // find_pattern engine local permissions to read /proc/self/maps mapping
            // blocks seamlessly with zero root permission errors.
            sandboxBootTrigger();

            Log.i(TAG, "SandboxCore initialized: VActivityManager=" + mVActivityManager + 
                    ", VPackageManager=" + mVPackageManager + ", ZygoteProxy=" + mZygoteProxy);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize sandbox", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public VActivityManager getVActivityManager() {
        return mVActivityManager;
    }

    public VPackageManager getVPackageManager() {
        return mVPackageManager;
    }

    public ZygoteProxy getZygoteProxy() {
        return mZygoteProxy;
    }

    // ============================================================
    // VActivityManager - Proxy to virtual activity manager
    // ============================================================
    public static class VActivityManager {
        private final Context mContext;
        private static VActivityManager sInstance;

        public VActivityManager(Context context) {
            mContext = context;
        }

        public static synchronized VActivityManager get(Context context) {
            if (sInstance == null) {
                sInstance = new VActivityManager(context.getApplicationContext());
            }
            return sInstance;
        }

        public static VActivityManager get() {
            return sInstance;
        }

        public int startActivity(Intent intent, int userId) {
            try {
                mContext.startActivity(intent);
                Log.d("VActivityManager", "startActivity: " + intent.getComponent());
                return 0;
            } catch (Exception e) {
                Log.e("VActivityManager", "startActivity failed", e);
                return -1;
            }
        }

        public void onActivityCreated(ComponentName component, IBinder token, Intent intent,
                                      String affinity, int taskId, int launchMode, int flags) {
            Log.d("VActivityManager", "onActivityCreated: " + component);
        }
    }

    // ============================================================
    // VPackageManager - Proxy to virtual package manager
    // ============================================================
    public static class VPackageManager {
        private final Context mContext;
        private static VPackageManager sInstance;

        public VPackageManager(Context context) {
            mContext = context;
        }

        public static synchronized VPackageManager get(Context context) {
            if (sInstance == null) {
                sInstance = new VPackageManager(context.getApplicationContext());
            }
            return sInstance;
        }

        public static VPackageManager get() {
            return sInstance;
        }

        public android.content.pm.PackageInfo getPackageInfo(String packageName, int flags, int userId) {
            try {
                return mContext.getPackageManager().getPackageInfo(packageName, flags);
            } catch (Exception e) {
                Log.e("VPackageManager", "getPackageInfo failed", e);
                return null;
            }
        }

        public java.util.List<android.content.pm.ApplicationInfo> getInstalledApplications(int flags, int userId) {
            try {
                return mContext.getPackageManager().getInstalledApplications(flags);
            } catch (Exception e) {
                Log.e("VPackageManager", "getInstalledApplications failed", e);
                return new java.util.ArrayList<>();
            }
        }
    }

    // ============================================================
    // ZygoteProxy - Virtual Zygote process proxy for forking sandboxed processes
    // ============================================================
    public static class ZygoteProxy {
        private final Context mContext;
        private static ZygoteProxy sInstance;

        public ZygoteProxy(Context context) {
            mContext = context;
        }

        public static synchronized ZygoteProxy get(Context context) {
            if (sInstance == null) {
                sInstance = new ZygoteProxy(context.getApplicationContext());
            }
            return sInstance;
        }

        public static ZygoteProxy get() {
            return sInstance;
        }

        public void preloadDexes(String[] dexPaths) {
            Log.i("ZygoteProxy", "Preloading DEX files: " + (dexPaths != null ? dexPaths.length : 0));
        }

        public void forkProcess(String targetPackage) {
            Log.i("ZygoteProxy", "Forking sandbox process for: " + targetPackage);
        }

        public boolean isProcessInSandbox(int pid) {
            return false;
        }
    }

    // ZygoteFragment runs in separate process for isolated sandbox
    public static class ZygoteFragment extends Service {
        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }
}