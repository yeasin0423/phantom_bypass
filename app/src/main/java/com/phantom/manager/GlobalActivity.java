package com.phantom.manager;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global settings: apply to every enabled app.
 *  - global custom system properties
 *  - global "files to hide" list
 *  - global "apps to hide" list
 *  - boot script (run once after boot by service.sh, ideal for resetprop)
 */
public class GlobalActivity extends Activity {

    private final Map<String, EditText> inputs = new LinkedHashMap<String, EditText>();
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActionBar().setTitle("Global Settings");

        JSONObject g = ConfigStore.get().global();

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        section("Global system properties (one per line:  key=value)");
        addMulti("props", "Applied to every enabled app (on top of per-app props)",
                ConfigStore.propsToText(g.optJSONObject("props")));

        section("Files to hide (one path per line)");
        addMulti("hide_files", "Extends the built-in root/emulator paths for all enabled apps",
                ConfigStore.listToText(g.optJSONArray("hide_files")));

        section("Apps to hide (one package per line)");
        addMulti("hide_apps", "Hidden from getPackageInfo / installed-app lists of all enabled apps",
                ConfigStore.listToText(g.optJSONArray("hide_apps")));

        section("Boot script (run once as root after boot)");
        addMulti("boot_scripts", "One command per line, e.g.:  resetprop ro.build.version.security_patch 2024-12-01",
                ConfigStore.listToText(g.optJSONArray("boot_scripts")));

        Button save = new Button(this);
        save.setText("Save global settings");
        save.setOnClickListener(v -> save());
        root.addView(save);

        Button runBoot = new Button(this);
        runBoot.setText("Run boot script now");
        runBoot.setOnClickListener(v -> {
            new Thread(() -> {
                Su.run("sh /data/adb/phantom/boot.sh 2>/dev/null");
                runOnUiThread(() -> Toast.makeText(this, "Boot script executed (root)", Toast.LENGTH_SHORT).show());
            }).start();
        });
        root.addView(runBoot);
    }

    private void save() {
        try {
            JSONObject g = ConfigStore.get().global();
            g.put("props", ConfigStore.propsFromText(inputs.get("props").getText().toString()));
            g.put("hide_files", ConfigStore.listFromText(inputs.get("hide_files").getText().toString()));
            g.put("hide_apps", ConfigStore.listFromText(inputs.get("hide_apps").getText().toString()));
            g.put("boot_scripts", ConfigStore.listFromText(inputs.get("boot_scripts").getText().toString()));
            new Thread(() -> {
                ConfigStore.get().saveAll();
                runOnUiThread(() -> Toast.makeText(this, "Global settings saved", Toast.LENGTH_SHORT).show());
            }).start();
        } catch (Exception ex) {
            Toast.makeText(this, "Save failed: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void section(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(0xff1a1a1a);
        t.setPadding(0, dp(18), 0, dp(6));
        root.addView(t);
    }

    private void addMulti(String key, String label, String value) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12);
        l.setTextColor(0xff666666);
        l.setPadding(0, dp(8), 0, dp(2));
        root.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextSize(14);
        e.setSingleLine(false);
        e.setMinLines(3);
        e.setGravity(Gravity.TOP);
        e.setBackgroundColor(0x11888888);
        e.setPadding(dp(6), dp(6), dp(6), dp(6));
        root.addView(e);
        inputs.put(key, e);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
