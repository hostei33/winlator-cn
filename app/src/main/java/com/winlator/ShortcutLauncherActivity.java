package com.winlator;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.AppUtils;

public class ShortcutLauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        int containerId = intent.getIntExtra("container_id", 0);
        String shortcutPath = intent.getStringExtra("shortcut_path");
        Container container = new ContainerManager(this).getContainerById(containerId);

        if (container == null || shortcutPath == null || shortcutPath.isEmpty()) {
            AppUtils.showToast(this, R.string.shortcut_not_found);
            finish();
            return;
        }

        Intent launchIntent = new Intent(this, XServerDisplayActivity.class);
        launchIntent.putExtra("container_id", containerId);
        launchIntent.putExtra("shortcut_path", shortcutPath);
        startActivity(launchIntent);
        overridePendingTransition(0, 0);
        finish();
    }
}
