package com.gotoubun;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class GameLauncher extends Activity {

    private static final String TAG = "GameLauncher";
    private Handler mHandler;
    private GridLayout mGamesGrid;
    private List<Button> mGameButtons;
    private Button mAddGameButton;
    private List<String> mInstalledGames;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_launcher);

        mHandler = new Handler();
        mGamesGrid = findViewById(R.id.games_grid);
        mAddGameButton = findViewById(R.id.btn_add_game);
        mGameButtons = new ArrayList<>();
        mInstalledGames = new ArrayList<>();

        setupUI();
        loadInstalledGamesAsync();

        mAddGameButton.setOnClickListener(v -> showAddGameDialog());
    }

    private void setupUI() {
        mGamesGrid.setRowCount(0);
        mGamesGrid.setColumnCount(1);

        mGamesGrid.addView(mAddGameButton);
    }

    private void loadInstalledGamesAsync() {
        new Thread(() -> {
            loadInstalledGames();
            mHandler.post(() -> displayGamesGrid());
        }).start();
    }

    private void loadInstalledGames() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            java.util.List<android.content.pm.ResolveInfo> resolveInfos = pm.queryIntentActivities(
                    mainIntent, PackageManager.GET_META_DATA);

            for (android.content.pm.ResolveInfo info : resolveInfos) {
                if (info.activityInfo.packageName.equals(getPackageName())) {
                    continue;
                }

                mInstalledGames.add(info.activityInfo.packageName);
            }

            mInstalledGames.add("com.android.settings");
            mInstalledGames.add("com.android.browser");

        } catch (Exception e) {
            Log.e(TAG, "Failed to load installed games", e);
        }
    }

    private void displayGamesGrid() {
        mGamesGrid.removeAllViews();

        for (int i = 0; i < mInstalledGames.size(); i++) {
            String packageName = mInstalledGames.get(i);
            Button gameButton = createGameButton(packageName, "Game " + (i + 1), "Default Icon");
            mGameButtons.add(gameButton);
            mGamesGrid.addView(gameButton);
        }

        mGamesGrid.addView(mAddGameButton);
    }

    private Button createGameButton(String packageName, String title, String iconDescription) {
        Button button = new Button(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.game_button_height)
        );
        params.setMargins(0, 0, 0, getResources().getDimensionPixelSize(R.dimen.game_button_margin));
        button.setLayoutParams(params);
        button.setText(title);
        button.setTextSize(16f);
        button.setPadding(8, 8, 8, 8);
        button.setOnClickListener(v -> launchGame(packageName, title));
        return button;
    }

    private void launchGame(String packageName, String title) {
        Toast.makeText(this, "Launching " + title + " (" + packageName + ")", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                cloneAndRunGame(packageName);
                mHandler.post(() -> Toast.makeText(GameLauncher.this, "Game launched successfully!", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch game", e);
                mHandler.post(() -> Toast.makeText(GameLauncher.this, "Failed to launch game: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void cloneAndRunGame(String packageName) {
        File cacheDir = getCacheDir();
        File cloneDir = new File(cacheDir, "cloned_games");
        cloneDir.mkdirs();

        File targetDir = new File(cloneDir, packageName.replace('.', '_'));
        if (targetDir.exists()) {
            android.content.pm.PackageManager pm = getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                startActivity(launchIntent);
                return;
            }
        }

        copyApkAndConfigs(packageName, targetDir);
        runGameInSandbox(targetDir, packageName);
    }

    private void copyApkAndConfigs(String packageName, File targetDir) {
        android.content.pm.PackageManager pm = getPackageManager();
        try {
            android.content.pm.PackageInfo packageInfo = pm.getPackageInfo(packageName, 0);

            File sourceFile = new File(packageInfo.applicationInfo.sourceDir);
            File destFile = new File(targetDir, "base.apk");
            android.os.FileUtils.copyFile(sourceFile, destFile);

            if (packageInfo.applicationInfo.splitSourceDirs != null) {
                for (String splitPath : packageInfo.applicationInfo.splitSourceDirs) {
                    File splitFile = new File(splitPath);
                    File destSplit = new File(targetDir, "splits/" + splitFile.getName());
                    destSplit.getParentFile().mkdirs();
                    android.os.FileUtils.copyFile(splitFile, destSplit);
                }
            }

            saveConfigToFile(packageName, targetDir, packageInfo);

        } catch (Exception e) {
            Log.e(TAG, "Failed to copy APK and configs", e);
        }
    }

    private void saveConfigToFile(String packageName, File targetDir, android.content.pm.PackageInfo packageInfo) {
        File configFile = new File(targetDir, "config.txt");
        try (java.io.BufferedWriter writer = new java.io.BufferedWriter(
                new java.io.FileWriter(configFile))) {

            writer.write("package_name=" + packageName + "\n");
            writer.write("version_code=" + packageInfo.versionCode + "\n");
            writer.write("version_name=" + packageInfo.versionName + "\n");
            writer.write("install_time=" + packageInfo.firstInstallTime + "\n");
            writer.write("update_time=" + packageInfo.lastUpdateTime + "\n");

            if (packageInfo.sharedUserId != null) {
                writer.write("shared_user_id=" + packageInfo.sharedUserId + "\n");
            }

            writer.write("activity_count=" + (packageInfo.activities != null ? packageInfo.activities.length : 0) + "\n");
            if (packageInfo.activities != null) {
                for (int i = 0; i < packageInfo.activities.length; i++) {
                    writer.write("activity_" + i + "=" + packageInfo.activities[i].name + "\n");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to save config", e);
        }
    }

    private void runGameInSandbox(File gameDir, String packageName) {
        Intent serviceIntent = new Intent(this, SandboxRuntimeService.class);
        serviceIntent.putExtra("game_dir", gameDir.getAbsolutePath());
        serviceIntent.putExtra("package_name", packageName);
        startService(serviceIntent);
    }

    private void showAddGameDialog() {
        Intent intent = new Intent(this, AddGameDialog.class);
        startActivityForResult(intent, 101);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 101 && resultCode == RESULT_OK) {
            String newPackageName = data.getStringExtra("package_name");
            if (newPackageName != null) {
                mInstalledGames.add(newPackageName);
                Button newButton = createGameButton(newPackageName, "Game " + (mInstalledGames.size()), "New Icon");
                mGameButtons.add(newButton);
                mGamesGrid.addView(newButton);
                mGamesGrid.removeView(mAddGameButton);
                mGamesGrid.addView(mAddGameButton);
            }
        }
    }
}