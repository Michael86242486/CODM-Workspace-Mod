package com.gotoubun;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    static {
        try {
            System.loadLibrary("va++");
            System.loadLibrary("nino");
        } catch (UnsatisfiedLinkError error) {
            Log.e(TAG, "Failed to load native library", error);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent launcherIntent = new Intent(this, GameLauncher.class);
        startActivity(launcherIntent);
        finish();
    }
}