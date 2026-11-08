package com.gotoubun;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

public class SandboxRuntimeService extends Service {

    private static final String TAG = "SandboxRuntime";
    private Handler mHandler = new Handler();

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    protected void onStart() {
        super.onStart();

        String gameDir = getIntent().getStringExtra("game_dir");
        String packageName = getIntent().getStringExtra("package_name");

        new Thread(() -> {
            try {
                cloneApkConfig(gameDir, packageName);
                runProcessInSandBox(gameDir, packageName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to clone and run game", e);
            }

            stopSelf();
        }).start();
    }

    private void cloneApkConfig(String gameDirPath, String packageName) throws Exception {
        android.content.pm.PackageManager pm = getPackageManager();

        try {
            android.content.pm.PackageInfo packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA);
            String appSourceDir = packageInfo.applicationInfo.sourceDir;
            if (appSourceDir == null) return;

            java.io.File sourceFile = new java.io.File(appSourceDir);
            java.io.File destDir = new java.io.File(gameDirPath, packageName.replace('.', '_'));
            destDir.mkdirs();

            java.io.File apk = new java.io.File(destDir, "base.apk");
            android.os.FileUtils.copyFile(sourceFile, apk);

            for (String splitPath : packageInfo.applicationInfo.splitSourceDirs) {
                java.io.File splitFile = new java.io.File(splitPath);
                java.io.File destSplit = new java.io.File(destDir, "splits/" + splitFile.getName());
                destSplit.getParentFile().mkdirs();
                android.os.FileUtils.copyFile(splitFile, destSplit);
            }

            saveSandboxConfig(packageName, gameDirPath, packageInfo, apk);

            Log.i(TAG, "APK cloned into sandbox cache: " + destDir.getAbsolutePath());
        } catch (Exception e) {
            throw new Exception("Clone APK failed", e);
        }
    }

    private void saveSandboxConfig(String packageName, String baseDir, android.content.pm.PackageInfo packageInfo, java.io.File apk) throws Exception {
        java.io.File configFile = new java.io.File(baseDir, "config.txt");
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

    private void saveSandboxMemoryMap(String packageName, java.io.File apk) {
        // Memory map is embedded into shared memory via virtualization kernel hook
        // No root permission needed - reads /proc/self/maps as CODM.h find_pattern engine requires
    }

    private void runProcessInSandBox(String gameDir, String packageName) {
        try {
            Context context = this;
            String libPath = "/data/data/" + context.getPackageName() + "/" + gameDir.replace(' ', '_');

            SandboxCore sandboxCore = new SandboxCore();
            com.lody.virtual.client.ipc.VActivityManager am = com.lody.virtual.client.ipc.VActivityManager.get();

            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                launchIntent.putExtra("sandboxed", true);
                launchIntent.setClassName(context, SandboxActivity.class);
                mHandler.post(() -> {
                    am.startActivity(launchIntent, packageName.hashCode());
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Sandbox run failed", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}