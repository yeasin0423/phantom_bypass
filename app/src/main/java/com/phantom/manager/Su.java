package com.phantom.manager;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Minimal root shell helper. All module configuration lives under
 * /data/adb/phantom/, which is only readable/writable by root, so the
 * manager app performs every file operation through the Magisk su binary.
 */
public final class Su {

    private Su() {}

    /** Runs commands in one su session; returns stdout ("" on failure). */
    public static String run(String... commands) {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            for (String c : commands) {
                os.writeBytes(c);
                os.writeBytes("\n");
            }
            os.writeBytes("exit\n");
            os.flush();
            os.close();

            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
            r.close();
            p.waitFor();
            try { p.getErrorStream().close(); } catch (Exception ignored) {}
            try { p.getOutputStream().close(); } catch (Exception ignored) {}
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** True when we can actually get root (uid 0). */
    public static boolean available() {
        String out = run("id -u").trim();
        return "0".equals(out);
    }

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Writes a text file to an arbitrary (root-only) path, 644. */
    public static boolean writeFile(String path, String content) {
        try {
            File tmp = File.createTempFile("phantom", ".tmp", App.ctx().getFilesDir());
            FileOutputStream fo = new FileOutputStream(tmp);
            fo.write(content.getBytes(StandardCharsets.UTF_8));
            fo.close();
            String r = run(
                    "cat " + q(tmp.getAbsolutePath()) + " > " + q(path),
                    "chmod 644 " + q(path),
                    "rm -f " + q(tmp.getAbsolutePath()));
            return r != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Writes an executable script to an arbitrary (root-only) path, 755. */
    public static boolean writeScript(String path, String content) {
        try {
            File tmp = File.createTempFile("phantom", ".sh", App.ctx().getFilesDir());
            FileOutputStream fo = new FileOutputStream(tmp);
            fo.write(content.getBytes(StandardCharsets.UTF_8));
            fo.close();
            String r = run(
                    "cat " + q(tmp.getAbsolutePath()) + " > " + q(path),
                    "chmod 755 " + q(path),
                    "rm -f " + q(tmp.getAbsolutePath()));
            return r != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Reads a root-only file ("null" string on failure). */
    public static String readFile(String path) {
        String out = run("cat " + q(path) + " 2>/dev/null");
        return out;
    }
}
