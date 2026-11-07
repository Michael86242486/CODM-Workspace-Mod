package com.gotoubun;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    static {
        try {
            System.loadLibrary("nino");
        } catch (UnsatisfiedLinkError error) {
            Log.e(TAG, "Failed to load native library 'nino'. App cannot initialize native hooks.", error);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            Launcher.Init(this);
        } catch (Exception error) {
            Log.e(TAG, "Launcher initialization failed", error);
            finish();
        }
    }
}
