package com.phantom.hooker;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.TrustManager;

/**
 * All hook decision logic, executed inside the target app process.
 *
 * The native Zygisk module delivers this class the raw config.json of
 * /data/adb/phantom/ (fetched through the root companion), then applies
 * LSPlant hooks that all funnel into {@link #dispatch(int, Method, Object[])}.
 *
 * Hook ids must stay in sync with the HOOKS table in zygisk/src/phantom.cpp.
 */
public final class HookBridge {

    private HookBridge() {}

    // ------------------------------------------------------------------
    // Hook ids (sync with phantom.cpp HOOKS table)
    // ------------------------------------------------------------------
    public static final int H_SYS_PROP_GET = 0;
    public static final int H_SYS_PROP_GET_DEF = 1;
    public static final int H_SYS_PROP_GET_INT = 2;
    public static final int H_SYS_PROP_GET_LONG = 3;
    public static final int H_SYS_PROP_GET_BOOL = 4;
    public static final int H_TM_GET_DEVICE_ID = 5;
    public static final int H_TM_GET_DEVICE_ID_SLOT = 6;
    public static final int H_TM_GET_IMEI = 7;
    public static final int H_TM_GET_IMEI_SLOT = 8;
    public static final int H_TM_GET_MEID = 9;
    public static final int H_TM_GET_MEID_SLOT = 10;
    public static final int H_TM_GET_SUBSCRIBER_ID = 11;
    public static final int H_TM_GET_SIM_SERIAL = 12;
    public static final int H_TM_GET_LINE1 = 13;
    public static final int H_TM_GET_VOICEMAIL = 14;
    public static final int H_TM_GET_NET_OP = 15;
    public static final int H_TM_GET_NET_OP_NAME = 16;
    public static final int H_TM_GET_NET_COUNTRY = 17;
    public static final int H_TM_GET_SIM_OP = 18;
    public static final int H_TM_GET_SIM_OP_NAME = 19;
    public static final int H_TM_GET_SIM_COUNTRY = 20;
    public static final int H_TM_GET_GROUP_ID = 21;
    public static final int H_SETTINGS_GET_STRING = 22;
    public static final int H_FILE_EXISTS = 23;
    public static final int H_FILE_CAN_READ = 24;
    public static final int H_EXEC_1 = 25;
    public static final int H_EXEC_2 = 26;
    public static final int H_EXEC_3 = 27;
    public static final int H_EXEC_4 = 28;
    public static final int H_EXEC_5 = 29;
    public static final int H_EXEC_6 = 30;
    public static final int H_PB_START = 31;
    public static final int H_PM_GET_PACKAGE_INFO = 32;
    public static final int H_PM_GET_APP_INFO = 33;
    public static final int H_PM_GET_INSTALLED_PACKAGES = 34;
    public static final int H_PM_GET_INSTALLED_APPS = 35;
    public static final int H_SSL_CTX_INIT = 36;
    public static final int H_TMF_GET_TMS = 37;
    public static final int H_CONSCRYPT_CHECK = 38;
    public static final int H_CONSCRYPT_CHECK_SOCK = 39;
    public static final int H_OKHTTP3_CHECK = 40;
    public static final int H_OKHTTP3_CHECK4 = 41;
    public static final int H_OKHTTP2_CHECK = 42;
    public static final int H_READ_LINE = 43;

    // ------------------------------------------------------------------
    // Native side helpers (implemented in phantom.cpp). Called during
    // init() to feed the native __system_property_get / openat / fopen
    // hooks with this app's prop map and hidden path list.
    // ------------------------------------------------------------------
    public static native void nativeAddProp(String key, String value);
    public static native void nativeAddHiddenPath(String path);

    // ------------------------------------------------------------------
    // Parsed profile state
    // ------------------------------------------------------------------
    private static volatile boolean ready = false;
    private static boolean rootHide = true;
    private static boolean emuHide = true;
    private static boolean sslBypass = true;
    private static boolean spoofTel = false;

    private static final Map<String, String> props = new ConcurrentHashMap<String, String>();
    private static final Set<String> hideApps = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final List<String> hideFiles = new CopyOnWriteArrayList<String>();

    // device identity
    private static String fModel, fDevice, fManufacturer, fBrand, fProduct,
            fHardware, fBoard, fFingerprint, fTags, fBuildId, fRelease;
    private static int fSdk = -1;

    // telephony identity
    private static String tAndroidId, tImei, tImei2, tMeid, tSubId, tSimSerial,
            tLine, tVoicemail, tNetOp, tNetOpName, tNetCountry,
            tSimOp, tSimOpName, tSimCountry, tGroupId;

    // ------------------------------------------------------------------
    // Built-in defaults (always active when the feature flag is on).
    // Users extend these through the Phantom Manager app.
    // ------------------------------------------------------------------
    private static final String[] BUILTIN_ROOT_FILES = {
            "/sbin/su", "/system/bin/su", "/system/xbin/su", "/system/sbin/su",
            "/su/bin/su", "/vendor/bin/su", "/system/sd/xbin/su",
            "/system/bin/.ext/.su", "/system/usr/we-need-root/su",
            "/system/bin/failsafe/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/xbin/daemonsu", "/system/app/Superuser.apk", "/system/app/SuperSU",
            "/system/etc/init.d/99SuperSUDaemon", "/dev/com.koushikdutta.superuser.daemon",
            "/system/xbin/busybox", "/sbin/busybox", "/system/bin/busybox",
            "/data/adb", "/data/adb/magisk", "/data/adb/magisk.db", "/data/adb/ksu",
            "/data/adb/modules", "/sbin/.magisk", "/cache/.disable_magisk",
            "/system/bin/magisk", "/system/xbin/magisk", "/data/local/tmp/frida-server",
            "/data/local/tmp/frida", "/data/local/tmp/re.frida.server", "/system/xbin/frida-server"
    };

    private static final String[] BUILTIN_EMU_FILES = {
            "/dev/qemu_pipe", "/dev/qemu_trace", "/dev/goldfish_pipe",
            "/dev/socket/qemud", "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace", "/system/bin/qemu-props", "/system/etc/init.goldfish.sh",
            "/system/etc/NOTICE.xml.gz", "/proc/tty/drivers", "/ fstab.goldfish",
            "/fstab.goldfish", "/fstab.ranchu", "/init.goldfish.rc", "/init.ranchu.rc",
            "/ueventd.goldfish.rc", "/ueventd.ranchu.rc"
    };

    private static final String[] BUILTIN_HIDE_APPS = {
            "com.topjohnwu.magisk", "com.phantom.manager", "me.weishu.exp",
            "io.github.lsposed.manager", "org.lsposed.manager",
            "com.tsng.hidemyapplist", "io.github.vvb2060.magiskhide",
            "eu.chainfire.supersu", "com.koushikdutta.superuser",
            "com.noshufou.android.su", "com.thirdparty.superuser",
            "com.devadvance.rootcloak", "com.devadvance.rootcloakplus",
            "de.robv.android.xposed.installer", "com.saurik.substrate",
            "com.android.vending.billing.inappbilling", "com.yellowes.su"
    };

    private static final String[] PROP_OVERRIDES_ROOT = {
            "ro.debuggable=0", "ro.secure=1", "ro.build.type=user",
            "ro.build.tags=release-keys", "ro.build.selinux=1",
            "service.adb.root=0", "ro.boot.verifiedbootstate=green",
            "ro.boot.flash.locked=1", "ro.boot.vbmeta.device_state=locked",
            "sys.oem_unlock_allowed=0", "ro.wifi.channels=", "ro.boot.selinux=permissive"
    };

    private static final String[] PROP_OVERRIDES_EMU = {
            "ro.kernel.qemu=0", "ro.boot.qemu=0", "qemu.hw.mainkeys=0",
            "init.svc.qemud=stopped", "init.svc.qemu-props=stopped",
            "ro.bootmode=unknown", "ro.bootmode=unknown2", "init.svc.goldfish-logcat=stopped",
            "init.svc.goldfish-setup=stopped", "ro.build.fingerprint=samsung/starltexx/starlte:10/QP1A.190711.020/G960FXXSDFUG5:user/release-keys"
    };

    // ------------------------------------------------------------------
    // Entry point called by native code in postAppSpecialize
    // ------------------------------------------------------------------
    public static void init(String config, String pkg) {
        try {
            parse(config, pkg);
        } catch (Throwable t) {
            try {
                android.util.Log.e("Phantom", "config parse failed for " + pkg, t);
            } catch (Throwable ignored) {
            }
        }
        ready = true;
    }

    // ------------------------------------------------------------------
    // Config parsing
    // ------------------------------------------------------------------
    private static void parse(String json, String pkg) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject global = root.optJSONObject("global");
        if (global == null) global = new JSONObject();
        JSONObject apps = root.optJSONObject("apps");
        JSONObject app = (apps == null) ? null : apps.optJSONObject(pkg);

        rootHide = (app == null) || app.optBoolean("root_hide", true);
        emuHide = (app == null) || app.optBoolean("emu_hide", true);
        sslBypass = (app == null) || app.optBoolean("ssl_bypass", true);
        spoofTel = (app != null) && app.optBoolean("spoof_telephony", false);

        JSONObject dev = (app == null) ? null : app.optJSONObject("device");
        if (dev != null) {
            fModel = nz(dev.optString("model"));
            fDevice = nz(dev.optString("device"));
            fManufacturer = nz(dev.optString("manufacturer"));
            fBrand = nz(dev.optString("brand"));
            fProduct = nz(dev.optString("product"));
            fHardware = nz(dev.optString("hardware"));
            fBoard = nz(dev.optString("board"));
            fFingerprint = nz(dev.optString("fingerprint"));
            fTags = nz(dev.optString("tags"));
            fBuildId = nz(dev.optString("id"));
            fRelease = nz(dev.optString("version_release"));
            int sdk = dev.optInt("sdk_int", 0);
            if (sdk > 0) fSdk = sdk;
        }

        JSONObject tel = (app == null) ? null : app.optJSONObject("telephony");
        if (tel != null) {
            tAndroidId = nz(tel.optString("android_id"));
            tImei = nz(tel.optString("imei"));
            tImei2 = nz(tel.optString("imei2"));
            tMeid = nz(tel.optString("meid"));
            tSubId = nz(tel.optString("subscriber_id"));
            tSimSerial = nz(tel.optString("sim_serial"));
            tLine = nz(tel.optString("line_number"));
            tVoicemail = nz(tel.optString("voice_mail"));
            tNetOp = nz(tel.optString("network_operator"));
            tNetOpName = nz(tel.optString("network_operator_name"));
            tNetCountry = nz(tel.optString("network_country"));
            tSimOp = nz(tel.optString("sim_operator"));
            tSimOpName = nz(tel.optString("sim_operator_name"));
            tSimCountry = nz(tel.optString("sim_country"));
            tGroupId = nz(tel.optString("group_id"));
        }

        // hidden files: built-ins + global list + per-app extras
        if (rootHide) addAll(hideFiles, BUILTIN_ROOT_FILES);
        if (emuHide) addAll(hideFiles, BUILTIN_EMU_FILES);
        addArr(hideFiles, global.optJSONArray("hide_files"));
        if (app != null) addArr(hideFiles, app.optJSONArray("extra_hide_files"));

        // hidden packages: built-ins + global list + per-app extras
        Collections.addAll(hideApps, BUILTIN_HIDE_APPS);
        addArr(hideApps, global.optJSONArray("hide_apps"));
        if (app != null) addArr(hideApps, app.optJSONArray("extra_hide_apps"));

        // props: built-in overrides -> device mapping -> telephony mapping
        //       -> global custom props -> per-app custom props (highest priority)
        if (rootHide) putLines(PROP_OVERRIDES_ROOT);
        if (emuHide) putLines(PROP_OVERRIDES_EMU);
        putDeviceProps();
        putTelephonyProps();
        putJsonProps(global.optJSONObject("props"));
        if (app != null) putJsonProps(app.optJSONObject("props"));

        // push everything to the native side (for __system_property_get /
        // openat / fopen hooks)
        for (Map.Entry<String, String> e : props.entrySet()) {
            nativeAddProp(e.getKey(), e.getValue());
        }
        for (String p : hideFiles) {
            if (p.length() > 0) nativeAddHiddenPath(p);
        }

        applyBuildFields();
    }

    private static String nz(String s) {
        return (s == null || "null".equals(s)) ? "" : s;
    }

    private static void addAll(List<String> list, String[] arr) {
        Collections.addAll(list, arr);
    }

    private static void addArr(Set<String> set, JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String v = nz(arr.optString(i));
            if (v.length() > 0) set.add(v);
        }
    }

    private static void addArr(List<String> list, JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String v = nz(arr.optString(i));
            if (v.length() > 0 && !list.contains(v)) list.add(v);
        }
    }

    /** key=value lines, '#' prefixed lines are comments. */
    private static void putLines(String[] lines) {
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            props.put(line.substring(0, eq), line.substring(eq + 1));
        }
    }

    private static void putJsonProps(JSONObject o) {
        if (o == null) return;
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            props.put(k, nz(o.optString(k)));
        }
    }

    private static void putDeviceProps() {
        if (fModel.length() > 0) props.put("ro.product.model", fModel);
        if (fDevice.length() > 0) props.put("ro.product.device", fDevice);
        if (fBrand.length() > 0) props.put("ro.product.brand", fBrand);
        if (fManufacturer.length() > 0) props.put("ro.product.manufacturer", fManufacturer);
        if (fProduct.length() > 0) props.put("ro.product.name", fProduct);
        if (fHardware.length() > 0) props.put("ro.hardware", fHardware);
        if (fBoard.length() > 0) props.put("ro.product.board", fBoard);
        if (fFingerprint.length() > 0) props.put("ro.build.fingerprint", fFingerprint);
        if (fTags.length() > 0) props.put("ro.build.tags", fTags);
        if (fBuildId.length() > 0) props.put("ro.build.id", fBuildId);
        if (fRelease.length() > 0) props.put("ro.build.version.release", fRelease);
        if (fSdk > 0) props.put("ro.build.version.sdk", String.valueOf(fSdk));
    }

    private static void putTelephonyProps() {
        if (!spoofTel) return;
        if (tNetOp.length() > 0) props.put("gsm.operator.numeric", tNetOp);
        if (tNetOpName.length() > 0) props.put("gsm.operator.alpha", tNetOpName);
        if (tNetCountry.length() > 0) props.put("gsm.operator.iso-country", tNetCountry);
        if (tSimOp.length() > 0) props.put("gsm.sim.operator.numeric", tSimOp);
        if (tSimOpName.length() > 0) props.put("gsm.sim.operator.alpha", tSimOpName);
        if (tSimCountry.length() > 0) props.put("gsm.sim.operator.iso-country", tSimCountry);
        props.put("gsm.sim.state", "READY");
        props.put("gsm.network.type", "LTE");
        props.put("gsm.version.ril-impl", "android qualcomm RIL 1.0");
        if (tImei.length() > 0) props.put("ril.icc.sim.num", "1");
    }

    // ------------------------------------------------------------------
    // Build field spoofing (direct static field writes, no hook needed
    // because every app process gets a fresh copy of these fields).
    // ------------------------------------------------------------------
    private static void applyBuildFields() {
        if (fModel.length() > 0) setStatic("android.os.Build", "MODEL", fModel);
        if (fDevice.length() > 0) setStatic("android.os.Build", "DEVICE", fDevice);
        if (fProduct.length() > 0) setStatic("android.os.Build", "PRODUCT", fProduct);
        if (fBrand.length() > 0) setStatic("android.os.Build", "BRAND", fBrand);
        if (fManufacturer.length() > 0) setStatic("android.os.Build", "MANUFACTURER", fManufacturer);
        if (fHardware.length() > 0) setStatic("android.os.Build", "HARDWARE", fHardware);
        if (fBoard.length() > 0) setStatic("android.os.Build", "BOARD", fBoard);
        if (fFingerprint.length() > 0) setStatic("android.os.Build", "FINGERPRINT", fFingerprint);
        if (fBuildId.length() > 0) setStatic("android.os.Build", "ID", fBuildId);
        if (fTags.length() > 0) {
            setStatic("android.os.Build", "TAGS", fTags);
        } else if (rootHide) {
            setStatic("android.os.Build", "TAGS", "release-keys");
        }
        if (rootHide) setStatic("android.os.Build", "TYPE", "user");
        if (fRelease.length() > 0) setStatic("android.os.Build$VERSION", "RELEASE", fRelease);
        if (fSdk > 0) setStatic("android.os.Build$VERSION", "SDK_INT", Integer.valueOf(fSdk));
    }

    private static void setStatic(String cls, String name, Object value) {
        try {
            Field f = Class.forName(cls).getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Dispatch: every LSPlant hook funnels here
    // ------------------------------------------------------------------
    public static Object dispatch(int id, Method backup, Object[] args) throws Throwable {
        if (!ready) return callOrig(backup, args);
        switch (id) {
            case H_SYS_PROP_GET:
            case H_SYS_PROP_GET_DEF:
                return sysProp(backup, args);
            case H_SYS_PROP_GET_INT:
                return sysPropInt(backup, args);
            case H_SYS_PROP_GET_LONG:
                return sysPropLong(backup, args);
            case H_SYS_PROP_GET_BOOL:
                return sysPropBool(backup, args);
            case H_TM_GET_DEVICE_ID:
                return maybe(tImei, backup, args);
            case H_TM_GET_DEVICE_ID_SLOT:
            case H_TM_GET_IMEI_SLOT:
                return slotImei(args) ? maybe2(tImei2, tImei, backup, args) : maybe(tImei, backup, args);
            case H_TM_GET_IMEI:
                return maybe(tImei, backup, args);
            case H_TM_GET_MEID:
                return maybe(tMeid, backup, args);
            case H_TM_GET_MEID_SLOT:
                return maybe(tMeid, backup, args);
            case H_TM_GET_SUBSCRIBER_ID:
                return maybe(tSubId, backup, args);
            case H_TM_GET_SIM_SERIAL:
                return maybe(tSimSerial, backup, args);
            case H_TM_GET_LINE1:
                return maybe(tLine, backup, args);
            case H_TM_GET_VOICEMAIL:
                return maybe(tVoicemail, backup, args);
            case H_TM_GET_NET_OP:
                return maybe(tNetOp, backup, args);
            case H_TM_GET_NET_OP_NAME:
                return maybe(tNetOpName, backup, args);
            case H_TM_GET_NET_COUNTRY:
                return maybe(tNetCountry, backup, args);
            case H_TM_GET_SIM_OP:
                return maybe(tSimOp, backup, args);
            case H_TM_GET_SIM_OP_NAME:
                return maybe(tSimOpName, backup, args);
            case H_TM_GET_SIM_COUNTRY:
                return maybe(tSimCountry, backup, args);
            case H_TM_GET_GROUP_ID:
                return maybe(tGroupId, backup, args);
            case H_SETTINGS_GET_STRING:
                if (args.length > 1 && "android_id".equals(args[1]) && tAndroidId.length() > 0) {
                    return tAndroidId;
                }
                return callOrig(backup, args);
            case H_FILE_EXISTS:
                return isHiddenFile(pathOf(args)) ? Boolean.FALSE : callOrig(backup, args);
            case H_FILE_CAN_READ:
                return isHiddenFile(pathOf(args)) ? Boolean.FALSE : callOrig(backup, args);
            case H_EXEC_1:
            case H_EXEC_2:
            case H_EXEC_3:
            case H_EXEC_4:
            case H_EXEC_5:
            case H_EXEC_6:
                if (args.length > 1 && commandBlocked(args[1])) return new Payloads.DummyProcess();
                return callOrig(backup, args);
            case H_PB_START:
                return pbStart(backup, args);
            case H_PM_GET_PACKAGE_INFO:
            case H_PM_GET_APP_INFO:
                if (args.length > 1 && args[1] instanceof String && hideApps.contains(args[1])) {
                    throw new PackageManager.NameNotFoundException((String) args[1]);
                }
                return callOrig(backup, args);
            case H_PM_GET_INSTALLED_PACKAGES:
                return Payloads.filterPackages((List) callOrig(backup, args), hideApps, true);
            case H_PM_GET_INSTALLED_APPS:
                return Payloads.filterPackages((List) callOrig(backup, args), hideApps, false);
            case H_SSL_CTX_INIT:
                if (sslBypass && args.length > 1) {
                    args[1] = Payloads.acceptAll();
                }
                return callOrig(backup, args);
            case H_TMF_GET_TMS:
                return sslBypass ? Payloads.acceptAll() : callOrig(backup, args);
            case H_CONSCRYPT_CHECK:
                return sslBypass ? null : callOrig(backup, args);
            case H_CONSCRYPT_CHECK_SOCK:
                return sslBypass ? new ArrayList() : callOrig(backup, args);
            case H_OKHTTP3_CHECK:
            case H_OKHTTP3_CHECK4:
            case H_OKHTTP2_CHECK:
                return sslBypass ? null : callOrig(backup, args);
            case H_READ_LINE:
                Object line = callOrig(backup, args);
                if (line instanceof String && isDirtyLine((String) line)) return "";
                return line;
            default:
                return callOrig(backup, args);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Calls the original implementation captured in the backup Method. */
    static Object callOrig(Method backup, Object[] args) throws Throwable {
        try {
            if (Modifier.isStatic(backup.getModifiers())) {
                return backup.invoke(null, args);
            }
            Object receiver = (args != null && args.length > 0) ? args[0] : null;
            Object[] rest = (args != null && args.length > 1)
                    ? Arrays.copyOfRange(args, 1, args.length)
                    : new Object[0];
            return backup.invoke(receiver, rest);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            throw (cause != null) ? cause : ite;
        }
    }

    private static Object sysProp(Method backup, Object[] args) throws Throwable {
        if (args.length > 0 && args[0] instanceof String) {
            String v = props.get(args[0]);
            if (v != null) return v;
        }
        return callOrig(backup, args);
    }

    private static Object sysPropInt(Method backup, Object[] args) throws Throwable {
        if (args.length > 0 && args[0] instanceof String) {
            String v = props.get(args[0]);
            if (v != null) {
                try {
                    return Integer.valueOf(Integer.parseInt(v.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return callOrig(backup, args);
    }

    private static Object sysPropLong(Method backup, Object[] args) throws Throwable {
        if (args.length > 0 && args[0] instanceof String) {
            String v = props.get(args[0]);
            if (v != null) {
                try {
                    return Long.valueOf(Long.parseLong(v.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return callOrig(backup, args);
    }

    private static Object sysPropBool(Method backup, Object[] args) throws Throwable {
        if (args.length > 0 && args[0] instanceof String) {
            String v = props.get(args[0]);
            if (v != null) {
                v = v.trim().toLowerCase(Locale.US);
                if ("1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v)) {
                    return Boolean.TRUE;
                }
                if ("0".equals(v) || "false".equals(v) || "no".equals(v) || "off".equals(v) || v.length() == 0) {
                    return Boolean.FALSE;
                }
            }
        }
        return callOrig(backup, args);
    }

    private static Object maybe(String fake, Method backup, Object[] args) throws Throwable {
        return (spoofTel && fake != null && fake.length() > 0) ? fake : callOrig(backup, args);
    }

    private static Object maybe2(String first, String second, Method backup, Object[] args) throws Throwable {
        if (!spoofTel) return callOrig(backup, args);
        String v = (first != null && first.length() > 0) ? first : second;
        return (v != null && v.length() > 0) ? v : callOrig(backup, args);
    }

    /** true when a getDeviceId(int)/getImei(int) call targets slot >= 1. */
    private static boolean slotImei(Object[] args) {
        try {
            return args.length > 1 && args[1] instanceof Integer && ((Integer) args[1]) >= 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String pathOf(Object[] args) {
        try {
            if (args.length > 0 && args[0] instanceof File) {
                return ((File) args[0]).getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static boolean isHiddenFile(String path) {
        if (path == null || path.length() == 0) return false;
        for (String entry : hideFiles) {
            if (path.contains(entry)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Command blocking (Runtime.exec / ProcessBuilder.start)
    // ------------------------------------------------------------------
    private static final String[] BAD_TOKENS = {
            "su", "busybox", "magisk", "daemonsu", "supersu", "superuser",
            "ksu", "frida", "xposed", "lsposed", "edxposed", "magiskhide"
    };

    private static boolean isBadToken(String t) {
        String s = t.toLowerCase(Locale.US);
        for (String bad : BAD_TOKENS) {
            if (s.equals(bad) || s.contains("/" + bad)) return true;
        }
        return false;
    }

    private static boolean commandBlocked(Object cmd) {
        try {
            String[] tokens;
            if (cmd instanceof String) {
                tokens = ((String) cmd).split("\\s+");
            } else if (cmd instanceof String[]) {
                tokens = (String[]) cmd;
            } else {
                return false;
            }
            for (int i = 0; i < tokens.length; i++) {
                if (isBadToken(tokens[i])) return true;
                String low = tokens[i].toLowerCase(Locale.US);
                if ((low.equals("which") || low.endsWith("/which"))
                        && i + 1 < tokens.length && isBadToken(tokens[i + 1])) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Object pbStart(Method backup, Object[] args) throws Throwable {
        try {
            if (args.length > 0 && args[0] instanceof java.lang.ProcessBuilder) {
                List<String> cmd = ((java.lang.ProcessBuilder) args[0]).command();
                if (cmd != null) {
                    StringBuilder sb = new StringBuilder();
                    for (String c : cmd) sb.append(c).append(' ');
                    if (commandBlocked(sb.toString())) return new Payloads.DummyProcess();
                }
            }
        } catch (Throwable ignored) {
        }
        return callOrig(backup, args);
    }

    // ------------------------------------------------------------------
    // Line filtering (BufferedReader.readLine) — cleans outputs of
    // `ls /proc/mounts`-style reads that would reveal root / emulator.
    // ------------------------------------------------------------------
    private static final String[] DIRTY_TOKENS = {
            "magisk", "frida", "daemonsu", "supersu", "busybox",
            "xposed", "lsposed", "edxposed", "qemu", "goldfish",
            "genymotion", "ranchu", "/su"
    };

    private static boolean isDirtyLine(String line) {
        String low = line.toLowerCase(Locale.US);
        for (String token : DIRTY_TOKENS) {
            if (low.contains(token)) return true;
        }
        return false;
    }
}
