package com.gotoubun;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class AddGameDialog extends Activity {

    private static final String TAG = "AddGameDialog";
    private ListView mListView;
    private List<String> mPackageList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_game);

        mListView = findViewById(R.id.add_game_list);
        mPackageList = new ArrayList<>();

        loadInstalledApps();
        setupListAdapter();

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_select_game).setOnClickListener(v -> {
            int position = mListView.getCheckedItemPosition();
            if (position >= 0 && position < mPackageList.size()) {
                String selected = mPackageList.get(position);
                Intent result = new Intent();
                result.putExtra("package_name", selected);
                setResult(RESULT_OK, result);
                finish();
            } else {
                Toast.makeText(AddGameDialog.this, "Please select a game", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadInstalledApps() {
        try {
            PackageManager pm = getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.GET_META_DATA);

            for (ResolveInfo info : resolveInfos) {
                if (info.activityInfo.packageName.equals(getPackageName())) {
                    continue;
                }

                mPackageList.add(info.activityInfo.packageName);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to load installed apps", e);
        }
    }

    private void setupListAdapter() {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, mPackageList) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                android.widget.TextView tv = (android.widget.TextView) v;
                tv.setTextColor(android.graphics.Color.WHITE);
                return v;
            }
        };
        mListView.setAdapter(adapter);

        mListView.setOnItemClickListener((parent, view, position, id) -> {
            mListView.setItemChecked(position, !mListView.isItemChecked(position));
        });
    }
}
