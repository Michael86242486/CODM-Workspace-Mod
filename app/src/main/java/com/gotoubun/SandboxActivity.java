package com.gotoubun;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class SandboxActivity extends Activity {

    private static final String TAG = "SandboxActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String packageName = getIntent().getStringExtra("target_package");

        Log.i(TAG, "SandboxActivity created for " + packageName);

        if (packageName != null) {
            Intent targetIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (targetIntent != null) {
                targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(targetIntent);
            }
        }

        finish();
    }
}