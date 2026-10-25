package com.phantom.manager;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-app profile editor: device identity, telephony/IMEI/carrier spoofing,
 * custom system properties, extra hide lists and launch scripts.
 */
public class ProfileActivity extends Activity {

    private static final String[][] DEVICE_FIELDS = {
            {"model", "Model (e.g. SM-G960F)"},
            {"device", "Device (e.g. starlte)"},
            {"manufacturer", "Manufacturer (e.g. samsung)"},
            {"brand", "Brand (e.g. samsung)"},
            {"product", "Product (e.g. starltexx)"},
            {"hardware", "Hardware (e.g. samsungexynos9810)"},
            {"board", "Board (e.g. exynos9810)"},
            {"fingerprint", "Build fingerprint"},
            {"tags", "Build tags (e.g. release-keys)"},
            {"id", "Build ID (e.g. QP1A.190711.020)"},
            {"version_release", "Android version (e.g. 10)"},
            {"sdk_int", "SDK int (e.g. 29, leave empty to keep)"},
    };

    private static final String[][] TELEPHONY_FIELDS = {
            {"android_id", "Android ID (Settings.Secure)"},
            {"imei", "IMEI (slot 0)"},
            {"imei2", "IMEI (slot 1)"},
            {"meid", "MEID"},
            {"subscriber_id", "IMSI (subscriber id)"},
            {"sim_serial", "SIM serial number"},
            {"line_number", "Phone number (Line1)"},
            {"voice_mail", "Voicemail number"},
            {"network_operator", "Network operator (PLMN, e.g. 47003)"},
            {"network_operator_name", "Network operator name"},
            {"network_country", "Network country ISO (e.g. bd)"},
            {"sim_operator", "SIM operator (PLMN)"},
            {"sim_operator_name", "SIM operator name (carrier)"},
            {"sim_country", "SIM country ISO"},
            {"group_id", "Group ID level 1"},
    };

    private String pkg;
    private final Map<String, EditText> inputs = new LinkedHashMap<String, EditText>();
    private final Map<String, CheckBox> toggles = new LinkedHashMap<String, CheckBox>();
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pkg = getIntent().getStringExtra("pkg");
        String label = getIntent().getStringExtra("label");
        if (label == null || label.length() == 0) label = pkg;

        JSONObject prof = ConfigStore.get().profile(pkg);
        if (prof == null) prof = ConfigStore.defaultProfile();
        JSONObject dev = prof.optJSONObject("device");
        if (dev == null) dev = new JSONObject();
        JSONObject tel = prof.optJSONObject("telephony");
        if (tel == null) tel = new JSONObject();

        getActionBar().setTitle(label);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        // --- feature toggles ---
        section("Bypass features");
        addToggle("root_hide", "Hide root (su / Magisk / busybox / frida)");
        addToggle("emu_hide", "Hide emulator (qemu / goldfish)");
        addToggle("ssl_bypass", "SSL unpinning (accept-all)");
        addToggle("spoof_telephony", "Spoof telephony identity (IMEI / IMSI / SIM / carrier)");
        toggles.get("root_hide").setChecked(prof.optBoolean("root_hide", true));
        toggles.get("emu_hide").setChecked(prof.optBoolean("emu_hide", true));
        toggles.get("ssl_bypass").setChecked(prof.optBoolean("ssl_bypass", true));
        toggles.get("spoof_telephony").setChecked(prof.optBoolean("spoof_telephony", false));

        // --- device identity ---
        section("Device identity (empty = keep real value)");
        for (String[] f : DEVICE_FIELDS) {
            String key = "device." + f[0];
            String value;
            if (f[0].equals("sdk_int")) {
                int sdk = dev.optInt("sdk_int", 0);
                value = sdk > 0 ? String.valueOf(sdk) : "";
            } else {
                value = dev.optString(f[0], "");
            }
            addField(key, f[1], value);
        }

        // --- telephony ---
        section("Telephony / SIM / carrier (empty = keep real value)");
        for (String[] f : TELEPHONY_FIELDS) {
            addField("telephony." + f[0], f[1], tel.optString(f[0], ""));
        }

        // --- multiline editors ---
        section("Custom system properties (one per line:  key=value)");
        addMulti("multi.props", "Injected into SystemProperties.get() and native __system_property_get()",
                ConfigStore.propsToText(prof.optJSONObject("props")));

        section("Extra files to hide (one path per line)");
        addMulti("multi.extra_hide_files", "Extends the built-in root/emulator file list",
                ConfigStore.listToText(prof.optJSONArray("extra_hide_files")));

        section("Extra apps to hide (one package per line)");
        addMulti("multi.extra_hide_apps", "Hidden from getPackageInfo / installed-app lists",
                ConfigStore.listToText(prof.optJSONArray("extra_hide_apps")));

        section("Launch scripts (root, run every time this app starts)");
        addMulti("multi.launch_scripts", "One command per line, e.g.:  resetprop ro.wifi.channels 11",
                ConfigStore.listToText(prof.optJSONArray("launch_scripts")));

        // --- buttons ---
        Button save = button("Save profile");
        save.setOnClickListener(v -> save());
        Button runNow = button("Run launch scripts now");
        runNow.setOnClickListener(v -> {
            new Thread(() -> {
                Su.run("sh /data/adb/phantom/scripts/" + pkg + ".sh 2>/dev/null");
                runOnUiThread(() -> Toast.makeText(this, "Launch scripts executed (root)", Toast.LENGTH_SHORT).show());
            }).start();
        });
    }

    private void save() {
        try {
            JSONObject prof = ConfigStore.defaultProfile();
            JSONObject existing = ConfigStore.get().profile(pkg);
            prof.put("enabled", existing != null && existing.optBoolean("enabled", false));

            for (String key : toggles.keySet()) {
                prof.put(key, toggles.get(key).isChecked());
            }

            JSONObject device = new JSONObject();
            JSONObject telephony = new JSONObject();
            for (Map.Entry<String, EditText> e : inputs.entrySet()) {
                String key = e.getKey();
                String val = e.getValue().getText().toString().trim();
                if (key.startsWith("multi.")) {
                    prof.put(key.substring(6), ConfigStore.listFromText(e.getValue().getText().toString()));
                } else if (key.startsWith("device.")) {
                    String field = key.substring(7);
                    if (val.length() == 0) continue;
                    if (field.equals("sdk_int")) {
                        try {
                            device.put("sdk_int", Integer.parseInt(val));
                        } catch (NumberFormatException ignored) {
                        }
                    } else {
                        device.put(field, val);
                    }
                } else if (key.startsWith("telephony.")) {
                    if (val.length() > 0) telephony.put(key.substring(10), val);
                }
            }
            prof.put("device", device);
            prof.put("telephony", telephony);

            new Thread(() -> {
                ConfigStore.get().saveProfile(pkg, prof);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Saved — takes effect on next app start (force-stop the app first)", Toast.LENGTH_LONG).show();
                });
            }).start();
        } catch (Exception ex) {
            Toast.makeText(this, "Save failed: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------
    // tiny programmatic form builder
    // ------------------------------------------------------------------
    private void section(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(0xff1a1a1a);
        t.setPadding(0, dp(18), 0, dp(6));
        root.addView(t);
    }

    private void addToggle(String key, String label) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        root.addView(c);
        toggles.put(key, c);
    }

    private void addField(String key, String label, String value) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12);
        l.setTextColor(0xff666666);
        l.setPadding(0, dp(8), 0, dp(2));
        root.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextSize(14);
        e.setSingleLine(true);
        root.addView(e);
        inputs.put(key, e);
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

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        b.setLayoutParams(lp);
        root.addView(b);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
