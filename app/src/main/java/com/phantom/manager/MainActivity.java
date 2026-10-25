package com.phantom.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.AdapterView;
import android.widget.ListView;

public class MainActivity extends Activity {

    private ConfigStore store;
    private AppListAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ConfigStore.init();
        store = ConfigStore.get();
        adapter = new AppListAdapter(this, store);

        ListView list = findViewById(R.id.list);
        list.setAdapter(adapter);
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            AppEntry entry = (AppEntry) adapter.getItem(position);
            Intent it = new Intent(this, ProfileActivity.class);
            it.putExtra("pkg", entry.pkg);
            it.putExtra("label", entry.label);
            startActivity(it);
            return true;
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry entry = (AppEntry) adapter.getItem(position);
            openProfile(entry);
        });

        getActionBar().setTitle("Phantom Manager");

        new Thread(() -> {
            boolean root = Su.available();
            runOnUiThread(() -> {
                if (!root) {
                    new AlertDialog.Builder(this)
                            .setTitle("Root required")
                            .setMessage("Phantom Manager needs Magisk su access to write the module configuration under /data/adb/phantom/. Grant su to this app in Magisk and reopen it.")
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }).start();
    }

    private void openProfile(AppEntry entry) {
        Intent it = new Intent(this, ProfileActivity.class);
        it.putExtra("pkg", entry.pkg);
        it.putExtra("label", entry.label);
        startActivity(it);
    }

    @Override
    protected void onResume() {
        super.onResume();
        new Thread(() -> {
            store.load();
            adapter.reload();
        }).start();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_global) {
            startActivity(new Intent(this, GlobalActivity.class));
            return true;
        } else if (id == R.id.action_refresh) {
            new Thread(() -> {
                store.load();
                adapter.reload();
            }).start();
            return true;
        } else if (id == R.id.action_help) {
            new AlertDialog.Builder(this)
                    .setTitle("How to use")
                    .setMessage(
                            "1. Toggle the checkbox to enable Phantom for an app (root/emulator/SSL bypass with default settings).\n\n" +
                            "2. Long-press an app (or tap it) to open its profile editor: custom device identity, IMEI/IMSI/SIM & carrier, custom system properties, extra files/apps to hide, and launch scripts.\n\n" +
                            "3. Changes take effect the next time the target app starts (force-stop it first).\n\n" +
                            "4. Use Global Settings for lists that apply to every enabled app and for the boot script (resetprop etc.).")
                    .setPositiveButton("OK", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
