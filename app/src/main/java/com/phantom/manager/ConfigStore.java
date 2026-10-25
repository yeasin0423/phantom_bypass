package com.phantom.manager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Holds the whole module configuration as JSON and persists it to
 * /data/adb/phantom/ through the Magisk su binary:
 *
 *   /data/adb/phantom/config.json          full configuration
 *   /data/adb/phantom/enabled/<pkg>        empty marker per enabled app
 *   /data/adb/phantom/scripts/<pkg>.sh     per-app launch scripts (root)
 *   /data/adb/phantom/boot.sh              global boot script (root)
 *
 * The Zygisk module reads these files via its root companion process.
 */
public final class ConfigStore {

    private static ConfigStore sInst;

    public static synchronized ConfigStore get() {
        return sInst;
    }

    public static synchronized void init() {
        if (sInst == null) sInst = new ConfigStore();
    }

    private JSONObject root = new JSONObject();

    private ConfigStore() {
        load();
    }

    // ------------------------------------------------------------------
    // JSON structure helpers
    // ------------------------------------------------------------------
    public synchronized JSONObject global() {
        JSONObject g = root.optJSONObject("global");
        if (g == null) {
            g = new JSONObject();
            try {
                g.put("props", new JSONObject());
                g.put("hide_files", new JSONArray());
                g.put("hide_apps", new JSONArray());
                g.put("boot_scripts", new JSONArray());
                root.put("global", g);
            } catch (Exception ignored) {
            }
        }
        return g;
    }

    public synchronized JSONObject apps() {
        JSONObject a = root.optJSONObject("apps");
        if (a == null) {
            a = new JSONObject();
            try {
                root.put("apps", a);
            } catch (Exception ignored) {
            }
        }
        return a;
    }

    public synchronized JSONObject profile(String pkg) {
        return apps().optJSONObject(pkg);
    }

    public synchronized JSONObject ensureProfile(String pkg) {
        JSONObject p = profile(pkg);
        if (p == null) {
            p = defaultProfile();
            try {
                apps().put(pkg, p);
            } catch (Exception ignored) {
            }
        }
        return p;
    }

    public static JSONObject defaultProfile() {
        JSONObject p = new JSONObject();
        try {
            p.put("enabled", true);
            p.put("root_hide", true);
            p.put("emu_hide", true);
            p.put("ssl_bypass", true);
            p.put("spoof_telephony", false);
            p.put("device", new JSONObject());
            p.put("telephony", new JSONObject());
            p.put("props", new JSONObject());
            p.put("extra_hide_files", new JSONArray());
            p.put("extra_hide_apps", new JSONArray());
            p.put("launch_scripts", new JSONArray());
        } catch (Exception ignored) {
        }
        return p;
    }

    public synchronized boolean isEnabled(String pkg) {
        JSONObject p = profile(pkg);
        return p != null && p.optBoolean("enabled", false);
    }

    public synchronized void setEnabled(String pkg, boolean enabled) {
        JSONObject p = ensureProfile(pkg);
        try {
            p.put("enabled", enabled);
        } catch (Exception ignored) {
        }
        saveAll();
    }

    public synchronized void saveProfile(String pkg, JSONObject profile) {
        try {
            if (!profile.has("enabled")) profile.put("enabled", true);
            apps().put(pkg, profile);
        } catch (Exception ignored) {
        }
        saveAll();
    }

    // ------------------------------------------------------------------
    // Load / persist
    // ------------------------------------------------------------------
    public synchronized final void load() {
        String out = Su.readFile("/data/adb/phantom/config.json");
        JSONObject parsed = null;
        if (out != null && out.trim().startsWith("{")) {
            try {
                parsed = new JSONObject(out.trim());
            } catch (Exception ignored) {
            }
        }
        if (parsed == null) parsed = new JSONObject();
        root = parsed;
        global();  // make sure both top-level objects exist
        apps();
    }

    /** Writes config.json, enable markers, per-app scripts and boot.sh. */
    public synchronized void saveAll() {
        try {
            File tmp = File.createTempFile("cfg", ".json", App.ctx().getFilesDir());
            FileOutputStream fo = new FileOutputStream(tmp);
            fo.write(root.toString().getBytes(StandardCharsets.UTF_8));
            fo.close();

            List<String> cmds = new ArrayList<String>();
            cmds.add("mkdir -p /data/adb/phantom/enabled /data/adb/phantom/scripts");
            cmds.add("cat '" + tmp.getAbsolutePath() + "' > /data/adb/phantom/config.json");
            cmds.add("chmod 644 /data/adb/phantom/config.json");

            JSONObject apps = apps();
            Iterator<String> it = apps.keys();
            while (it.hasNext()) {
                String pkg = it.next();
                JSONObject p = apps.optJSONObject(pkg);
                if (p == null) continue;
                if (p.optBoolean("enabled", false)) {
                    cmds.add("touch /data/adb/phantom/enabled/" + pkg);
                } else {
                    cmds.add("rm -f /data/adb/phantom/enabled/" + pkg);
                }
                JSONArray ls = p.optJSONArray("launch_scripts");
                if (ls != null && ls.length() > 0) {
                    StringBuilder sb = new StringBuilder("#!/system/bin/sh\n");
                    for (int i = 0; i < ls.length(); i++) {
                        sb.append(ls.optString(i, "")).append('\n');
                    }
                    writeScriptTo(cmds, "/data/adb/phantom/scripts/" + pkg + ".sh", sb.toString(), "755");
                } else {
                    cmds.add("rm -f /data/adb/phantom/scripts/" + pkg + ".sh");
                }
            }

            JSONArray bs = global().optJSONArray("boot_scripts");
            if (bs != null && bs.length() > 0) {
                StringBuilder sb = new StringBuilder("#!/system/bin/sh\n");
                for (int i = 0; i < bs.length(); i++) {
                    sb.append(bs.optString(i, "")).append('\n');
                }
                writeScriptTo(cmds, "/data/adb/phantom/boot.sh", sb.toString(), "755");
            } else {
                cmds.add("rm -f /data/adb/phantom/boot.sh");
            }

            Su.run(cmds.toArray(new String[0]));
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void writeScriptTo(List<String> cmds, String remotePath, String content, String mode) {
        try {
            File tmp = File.createTempFile("script", ".sh", App.ctx().getFilesDir());
            FileOutputStream fo = new FileOutputStream(tmp);
            fo.write(content.getBytes(StandardCharsets.UTF_8));
            fo.close();
            cmds.add("cat '" + tmp.getAbsolutePath() + "' > '" + remotePath + "'");
            cmds.add("chmod " + mode + " '" + remotePath + "'");
            cmds.add("rm -f '" + tmp.getAbsolutePath() + "'");
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Text <-> JSON conversion for the editor screens
    // ------------------------------------------------------------------

    /** "a=b" lines -> JSONObject */
    public static JSONObject propsFromText(String text) {
        JSONObject o = new JSONObject();
        if (text == null) return o;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            try {
                o.put(line.substring(0, eq).trim(), line.substring(eq + 1));
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    public static String propsToText(JSONObject o) {
        if (o == null) return "";
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            sb.append(k).append('=').append(o.optString(k)).append('\n');
        }
        return sb.toString();
    }

    /** one item per line -> JSONArray */
    public static JSONArray listFromText(String text) {
        JSONArray a = new JSONArray();
        if (text == null) return a;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            a.put(line);
        }
        return a;
    }

    public static String listToText(JSONArray a) {
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            sb.append(a.optString(i)).append('\n');
        }
        return sb.toString();
    }
}
