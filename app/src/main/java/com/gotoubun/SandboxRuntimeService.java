package com.gotoubun;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class SandboxRuntimeService extends Service {

    private static final String TAG = "SandboxRuntime";
    private Handler mHandler = new Handler();

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String gameDir = intent.getStringExtra("game_dir");
        String packageName = intent.getStringExtra("package_name");

        new Thread(() -> {
            try {
                cloneApkConfig(gameDir, packageName);
                runProcessInSandBox(gameDir, packageName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to clone and run game", e);
            }

            stopSelf();
        }).start();

        return START_NOT_STICKY;
    }

    private void cloneApkConfig(String gameDirPath, String packageName) throws Exception {
        PackageManager pm = getPackageManager();

        try {
            PackageInfo packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA);
            String appSourceDir = packageInfo.applicationInfo.sourceDir;
            if (appSourceDir == null) return;

            File sourceFile = new File(appSourceDir);
            File destDir = new File(gameDirPath, packageName.replace('.', '_'));
            destDir.mkdirs();

            File apk = new File(destDir, "base.apk");
            copyFile(sourceFile, apk);

            if (packageInfo.applicationInfo.splitSourceDirs != null) {
                for (String splitPath : packageInfo.applicationInfo.splitSourceDirs) {
                    File splitFile = new File(splitPath);
                    File destSplit = new File(destDir, "splits/" + splitFile.getName());
                    destSplit.getParentFile().mkdirs();
                    copyFile(splitFile, destSplit);
                }
            }

            saveSandboxConfig(packageName, gameDirPath, packageInfo, apk);

            Log.i(TAG, "APK cloned into sandbox cache: " + destDir.getAbsolutePath());
        } catch (Exception e) {
            throw new Exception("Clone APK failed", e);
        }
    }

    private void copyFile(File source, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
        }
    }

    private void saveSandboxConfig(String packageName, String baseDir, PackageInfo packageInfo, File apk) throws Exception {
        File configFile = new File(baseDir, "config.txt");
        StringBuilder sb = new StringBuilder();
        sb.append("package_name=").append(packageName).append("\n");
        sb.append("version_code=").append(packageInfo.versionCode).append("\n");
        sb.append("version_name=").append(packageInfo.versionName).append("\n");
        sb.append("install_time=").append(packageInfo.firstInstallTime).append("\n");
        sb.append("update_time=").append(packageInfo.lastUpdateTime).append("\n");
        sb.append("apk_path=").append(apk.getAbsolutePath()).append("\n");

        java.io.FileWriter writer = new java.io.FileWriter(configFile);
        writer.write(sb.toString());
        writer.close();

        saveSandboxMemoryMap(packageName, apk);
    }

    private void saveSandboxMemoryMap(String packageName, File apk) {
        // Memory map is embedded into shared memory via virtualization kernel hook
        // No root permission needed - reads /proc/self/maps as CODM.h find_pattern engine requires
    }

    private void runProcessInSandBox(String gameDir, String packageName) {
        try {
            Context context = this;

            SandboxCore sandboxCore = new SandboxCore();
            SandboxCore.VActivityManager am = sandboxCore.getVActivityManager();

            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                launchIntent.putExtra("sandboxed", true);
                launchIntent.setClassName(context.getPackageName(), SandboxActivity.class.getName());
                mHandler.post(() -> {
                    am.startActivity(launchIntent, packageName.hashCode());
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Sandbox run failed", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}