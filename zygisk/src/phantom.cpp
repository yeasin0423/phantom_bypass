// ============================================================================
//  Phantom Bypass — Zygisk module
//
//  Per-app root / emulator / SSL-pinning bypass, ported from the original
//  Frida universal bypass script (Daniele Linguaglossa / Simone Quatrini).
//
//  Architecture:
//   - Zygisk injects this .so into every app process.
//   - preAppSpecialize: ask the ROOT COMPANION (registered below) for the
//     config of this package. The companion checks the enable marker,
//     asynchronously runs the app's launch script (root context) and sends
//     back /data/adb/phantom/config.json. Non-enabled apps are left alone.
//   - postAppSpecialize: LSPlant is initialized (Dobby inline hooks +
//     SandHook ElfImg for libart symbols), the embedded hooker DEX is
//     loaded and 44 Java methods are hooked. All decision logic lives in
//     Java (hooker/src) — this file only wires things up.
//   - Additionally Dobby hooks libc: __system_property_get (per-app props),
//     openat / fopen / faccessat (file hiding at the native layer).
// ============================================================================

#include <jni.h>
#include <android/log.h>

#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <dlfcn.h>
#include <fcntl.h>
#include <functional>
#include <signal.h>
#include <sys/mman.h>
#include <unistd.h>

#include <dobby.h>
#include <lsplant.hpp>

// Dobby (pinned @ 6f065cf) returns plain int from DobbyHook/DobbyDestroy:
// 0 = success, -1 = failure. The RS_SUCCESS/RT_SUCCESS enums only exist in
// newer (refactor-era) Dobby, so alias them here for readability.
#ifndef RS_SUCCESS
#define RS_SUCCESS 0
#endif
#ifndef RT_SUCCESS
#define RT_SUCCESS 0
#endif

#include "elf_util.h"
#include "zygisk.hpp"

#include "dex_bytes.h"

#define LOG_TAG "Phantom"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace zygisk;

#ifndef PROP_VALUE_MAX
#define PROP_VALUE_MAX 92
#endif

// ---------------------------------------------------------------------------
// Small fd helpers
// ---------------------------------------------------------------------------
static bool readFull(int fd, void *buf, size_t len) {
    auto *p = static_cast<char *>(buf);
    size_t got = 0;
    while (got < len) {
        ssize_t n = read(fd, p + got, len - got);
        if (n > 0) {
            got += static_cast<size_t>(n);
        } else if (n < 0 && errno == EINTR) {
            continue;
        } else {
            return false;
        }
    }
    return true;
}

static bool writeFull(int fd, const void *buf, size_t len) {
    auto *p = static_cast<const char *>(buf);
    size_t put = 0;
    while (put < len) {
        ssize_t n = write(fd, p + put, len - put);
        if (n >= 0) {
            put += static_cast<size_t>(n);
        } else if (errno == EINTR) {
            continue;
        } else {
            return false;
        }
    }
    return true;
}

static std::string readFileAll(const char *path) {
    std::string out;
    FILE *f = fopen(path, "rb");
    if (!f) return out;
    char buf[8192];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) out.append(buf, n);
    fclose(f);
    return out;
}

// ---------------------------------------------------------------------------
// Hook ids — MUST mirror the public static final ints in HookBridge.java
// ---------------------------------------------------------------------------
namespace HookBridge {
enum HookId : int {
    H_SYS_PROP_GET = 0,
    H_SYS_PROP_GET_DEF,
    H_SYS_PROP_GET_INT,
    H_SYS_PROP_GET_LONG,
    H_SYS_PROP_GET_BOOL,
    H_TM_GET_DEVICE_ID,
    H_TM_GET_DEVICE_ID_SLOT,
    H_TM_GET_IMEI,
    H_TM_GET_IMEI_SLOT,
    H_TM_GET_MEID,
    H_TM_GET_MEID_SLOT,
    H_TM_GET_SUBSCRIBER_ID,
    H_TM_GET_SIM_SERIAL,
    H_TM_GET_LINE1,
    H_TM_GET_VOICEMAIL,
    H_TM_GET_NET_OP,
    H_TM_GET_NET_OP_NAME,
    H_TM_GET_NET_COUNTRY,
    H_TM_GET_SIM_OP,
    H_TM_GET_SIM_OP_NAME,
    H_TM_GET_SIM_COUNTRY,
    H_TM_GET_GROUP_ID,
    H_SETTINGS_GET_STRING,
    H_FILE_EXISTS,
    H_FILE_CAN_READ,
    H_EXEC_1,
    H_EXEC_2,
    H_EXEC_3,
    H_EXEC_4,
    H_EXEC_5,
    H_EXEC_6,
    H_PB_START,
    H_PM_GET_PACKAGE_INFO,
    H_PM_GET_APP_INFO,
    H_PM_GET_INSTALLED_PACKAGES,
    H_PM_GET_INSTALLED_APPS,
    H_SSL_CTX_INIT,
    H_TMF_GET_TMS,
    H_CONSCRYPT_CHECK,
    H_CONSCRYPT_CHECK_SOCK,
    H_OKHTTP3_CHECK,
    H_OKHTTP3_CHECK4,
    H_OKHTTP2_CHECK,
    H_READ_LINE,
};
}  // namespace HookBridge

// ---------------------------------------------------------------------------
// Per-process state (reset in preAppSpecialize, filled by Java via JNI)
// ---------------------------------------------------------------------------
static std::string g_config;
static char g_pkg[256] = {0};
static std::mutex g_mtx;
static std::unordered_map<std::string, std::string> g_props;
static std::vector<std::string> g_paths;
static bool g_hooks_done = false;

static bool isHiddenPath(const char *path) {
    if (path == nullptr || path[0] == '\0') return false;
    std::lock_guard<std::mutex> lk(g_mtx);
    if (g_paths.empty()) return false;
    for (const auto &e : g_paths) {
        if (strstr(path, e.c_str()) != nullptr) return true;
    }
    return false;
}

// ---------------------------------------------------------------------------
// JNI natives called from HookBridge.init() (Java side parses the config)
// ---------------------------------------------------------------------------
static void JNICALL nativeAddProp(JNIEnv *env, jclass, jstring key, jstring value) {
    if (key == nullptr || value == nullptr) return;
    const char *k = env->GetStringUTFChars(key, nullptr);
    const char *v = env->GetStringUTFChars(value, nullptr);
    if (k && v) {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_props[k] = v;
    }
    if (k) env->ReleaseStringUTFChars(key, k);
    if (v) env->ReleaseStringUTFChars(value, v);
}

static void JNICALL nativeAddHiddenPath(JNIEnv *env, jclass, jstring path) {
    if (path == nullptr) return;
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_paths.emplace_back(p);
    }
    if (p) env->ReleaseStringUTFChars(path, p);
}

// ---------------------------------------------------------------------------
// libc hooks (Dobby) — native-layer file hiding + property spoofing
// ---------------------------------------------------------------------------
static int (*orig_openat)(int, const char *, int, ...) = nullptr;
static FILE *(*orig_fopen)(const char *, const char *) = nullptr;
static int (*orig_faccessat)(int, const char *, int, int) = nullptr;
static int (*orig_prop_get)(const char *, char *) = nullptr;

static int my_openat(int dirfd, const char *pathname, int flags, ...) {
    if (isHiddenPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    va_list ap;
    va_start(ap, flags);
    int mode = va_arg(ap, int);
    va_end(ap);
    return orig_openat(dirfd, pathname, flags, mode);
}

static FILE *my_fopen(const char *pathname, const char *mode) {
    if (isHiddenPath(pathname)) {
        errno = ENOENT;
        return nullptr;
    }
    return orig_fopen(pathname, mode);
}

static int my_faccessat(int dirfd, const char *pathname, int mode, int flags) {
    if (isHiddenPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    return orig_faccessat(dirfd, pathname, mode, flags);
}

static int my_prop_get(const char *name, char *value) {
    if (name != nullptr && value != nullptr) {
        std::lock_guard<std::mutex> lk(g_mtx);
        auto it = g_props.find(name);
        if (it != g_props.end()) {
            strncpy(value, it->second.c_str(), PROP_VALUE_MAX - 1);
            value[PROP_VALUE_MAX - 1] = '\0';
            return static_cast<int>(strlen(value));
        }
    }
    return orig_prop_get(name, value);
}

static void makeRwx(void *p, size_t n) {
    uintptr_t addr = reinterpret_cast<uintptr_t>(p);
    uintptr_t page = 4096;
    uintptr_t start = addr & ~(page - 1);
    uintptr_t end = (addr + n + page - 1) & ~(page - 1);
    mprotect(reinterpret_cast<void *>(start), end - start,
             PROT_READ | PROT_WRITE | PROT_EXEC);
}

static void hookLibcFunctions() {
    void *p;
    if (!orig_prop_get && (p = dlsym(RTLD_DEFAULT, "__system_property_get")) != nullptr) {
        if (DobbyHook(p, reinterpret_cast<void *>(my_prop_get),
                      reinterpret_cast<void **>(&orig_prop_get)) == RS_SUCCESS)
            LOGI("hooked __system_property_get");
    }
    if (!orig_openat && (p = dlsym(RTLD_DEFAULT, "openat")) != nullptr) {
        if (DobbyHook(p, reinterpret_cast<void *>(my_openat),
                      reinterpret_cast<void **>(&orig_openat)) == RS_SUCCESS)
            LOGI("hooked openat");
    }
    if (!orig_fopen && (p = dlsym(RTLD_DEFAULT, "fopen")) != nullptr) {
        if (DobbyHook(p, reinterpret_cast<void *>(my_fopen),
                      reinterpret_cast<void **>(&orig_fopen)) == RS_SUCCESS)
            LOGI("hooked fopen");
    }
    if (!orig_faccessat && (p = dlsym(RTLD_DEFAULT, "faccessat")) != nullptr) {
        if (DobbyHook(p, reinterpret_cast<void *>(my_faccessat),
                      reinterpret_cast<void **>(&orig_faccessat)) == RS_SUCCESS)
            LOGI("hooked faccessat");
    }
}

// ---------------------------------------------------------------------------
// Hook table — MUST stay in sync with the H_* ids in HookBridge.java
// ---------------------------------------------------------------------------
struct HookDef {
    int id;
    const char *cls;
    const char *name;
    const char *sig;
    bool isStatic;
};

static const HookDef HOOKS[] = {
    // ---- SystemProperties (native hook covers NDK callers, this covers Java) ----
    {HookBridge::H_SYS_PROP_GET,     "android/os/SystemProperties", "get",        "(Ljava/lang/String;)Ljava/lang/String;", true},
    {HookBridge::H_SYS_PROP_GET_DEF, "android/os/SystemProperties", "get",        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", true},
    {HookBridge::H_SYS_PROP_GET_INT, "android/os/SystemProperties", "getInt",     "(Ljava/lang/String;I)I", true},
    {HookBridge::H_SYS_PROP_GET_LONG,"android/os/SystemProperties", "getLong",    "(Ljava/lang/String;J)J", true},
    {HookBridge::H_SYS_PROP_GET_BOOL,"android/os/SystemProperties", "getBoolean", "(Ljava/lang/String;Z)Z", true},
    // ---- TelephonyManager (IMEI / IMSI / carrier / operator spoofing) ----
    {HookBridge::H_TM_GET_DEVICE_ID,      "android/telephony/TelephonyManager", "getDeviceId",          "()Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_DEVICE_ID_SLOT, "android/telephony/TelephonyManager", "getDeviceId",          "(I)Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_IMEI,           "android/telephony/TelephonyManager", "getImei",              "()Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_IMEI_SLOT,      "android/telephony/TelephonyManager", "getImei",              "(I)Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_MEID,           "android/telephony/TelephonyManager", "getMeid",              "()Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_MEID_SLOT,      "android/telephony/TelephonyManager", "getMeid",              "(I)Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_SUBSCRIBER_ID,  "android/telephony/TelephonyManager", "getSubscriberId",      "()Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_SIM_SERIAL,     "android/telephony/TelephonyManager", "getSimSerialNumber",   "()Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_LINE1,          "android/telephony/TelephonyManager", "getLine1Number",       "()Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_VOICEMAIL,      "android/telephony/TelephonyManager", "getVoiceMailNumber",   "()Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_NET_OP,         "android/telephony/TelephonyManager", "getNetworkOperator",   "()Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_NET_OP_NAME,    "android/telephony/TelephonyManager", "getNetworkOperatorName", "()Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_NET_COUNTRY,    "android/telephony/TelephonyManager", "getNetworkCountryIso", "()Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_SIM_OP,         "android/telephony/TelephonyManager", "getSimOperator",       "()Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_SIM_OP_NAME,    "android/telephony/TelephonyManager", "getSimOperatorName",   "()Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_SIM_COUNTRY,    "android/telephony/TelephonyManager", "getSimCountryIso",     "()Ljava/lang/String;", false},
    {HookBridge::H_TM_GET_GROUP_ID,       "android/telephony/TelephonyManager", "getGroupIdLevel1",     "()Ljava/lang/String;", false},
    // ---- Settings.Secure ANDROID_ID ----
    {HookBridge::H_SETTINGS_GET_STRING, "android/provider/Settings$Secure", "getString", "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;", true},
    // ---- File hiding (Java layer; native openat/fopen covers NDK) ----
    {HookBridge::H_FILE_EXISTS,  "java/io/File", "exists",  "()Z", false},
    {HookBridge::H_FILE_CAN_READ,"java/io/File", "canRead", "()Z", false},
    // ---- Command blocking ----
    {HookBridge::H_EXEC_1, "java/lang/Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;", false},
    {HookBridge::H_EXEC_2, "java/lang/Runtime", "exec", "([Ljava/lang/String;)Ljava/lang/Process;", false},
    {HookBridge::H_EXEC_3, "java/lang/Runtime", "exec", "(Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;", false},
    {HookBridge::H_EXEC_4, "java/lang/Runtime", "exec", "([Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;", false},
    {HookBridge::H_EXEC_5, "java/lang/Runtime", "exec", "(Ljava/lang/String;[Ljava/lang/String;Ljava/io/File;)Ljava/lang/Process;", false},
    {HookBridge::H_EXEC_6, "java/lang/Runtime", "exec", "([Ljava/lang/String;[Ljava/lang/String;Ljava/io/File;)Ljava/lang/Process;", false},
    {HookBridge::H_PB_START, "java/lang/ProcessBuilder", "start", "()Ljava/lang/Process;", false},
    // ---- Package hiding (hook the concrete impl class) ----
    {HookBridge::H_PM_GET_PACKAGE_INFO,       "android/app/ApplicationPackageManager", "getPackageInfo",         "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;", false},
    {HookBridge::H_PM_GET_APP_INFO,           "android/app/ApplicationPackageManager", "getApplicationInfo",     "(Ljava/lang/String;I)Landroid/content/pm/ApplicationInfo;", false},
    {HookBridge::H_PM_GET_INSTALLED_PACKAGES, "android/app/ApplicationPackageManager", "getInstalledPackages",   "(I)Ljava/util/List;", false},
    {HookBridge::H_PM_GET_INSTALLED_APPS,     "android/app/ApplicationPackageManager", "getInstalledApplications", "(I)Ljava/util/List;", false},
    // ---- SSL unpinning ----
    {HookBridge::H_SSL_CTX_INIT, "javax/net/ssl/SSLContext", "init", "([Ljavax/net/ssl/KeyManager;[Ljavax/net/ssl/TrustManager;Ljava/security/SecureRandom;)V", false},
    {HookBridge::H_TMF_GET_TMS,  "javax/net/ssl/TrustManagerFactory", "getTrustManagers", "()[Ljavax/net/ssl/TrustManager;", false},
    {HookBridge::H_CONSCRYPT_CHECK,     "com/android/org/conscrypt/TrustManagerImpl", "checkServerTrusted", "([Ljava/security/cert/X509Certificate;Ljava/lang/String;)V", false},
    {HookBridge::H_CONSCRYPT_CHECK_SOCK,"com/android/org/conscrypt/TrustManagerImpl", "checkServerTrusted", "([Ljava/security/cert/X509Certificate;Ljava/lang/String;Ljava/net/Socket;)Ljava/util/List;", false},
    {HookBridge::H_OKHTTP3_CHECK,  "okhttp3/CertificatePinner", "check",        "(Ljava/lang/String;Ljava/util/List;)V", false},
    {HookBridge::H_OKHTTP3_CHECK4, "okhttp3/CertificatePinner", "check$okhttp", "(Ljava/lang/String;Ljava/util/List;Lkotlin/jvm/functions/Function1;)V", false},
    {HookBridge::H_OKHTTP2_CHECK,  "com/squareup/okhttp/CertificatePinner", "check", "(Ljava/lang/String;Ljava/util/List;)V", false},
    // ---- Output filtering (mounts / ls style reads) ----
    {HookBridge::H_READ_LINE, "java/io/BufferedReader", "readLine", "()Ljava/lang/String;", false},
};

// ---------------------------------------------------------------------------
// Hook installation
// ---------------------------------------------------------------------------
static void setupHooks(JNIEnv *env) {
    if (g_hooks_done) return;
    g_hooks_done = true;

    // ---- 1. resolve libart symbols and initialize LSPlant ----
    static SandHook::ElfImg art("libart.so");
    if (!art.isValid()) {
        LOGE("ElfImg: libart.so not found");
        return;
    }
    lsplant::InitInfo info{
        .inline_hooker = [](void *target, void *hooker) -> void * {
            makeRwx(target, 64);
            void *backup = nullptr;
            if (DobbyHook(target, hooker, &backup) == RS_SUCCESS) return backup;
            return nullptr;
        },
        .inline_unhooker = [](void *func) -> bool {
            return DobbyDestroy(func) == RT_SUCCESS;
        },
        .art_symbol_resolver = [](std::string_view symbol) -> void * {
            return reinterpret_cast<void *>(art.getSymbAddress(symbol));
        },
    };
    if (!lsplant::Init(env, info)) {
        LOGE("lsplant::Init failed");
        return;
    }
    LOGI("lsplant initialized");

    // ---- 2. load the embedded hooker DEX ----
    if (classes_dex_len < 8) {
        LOGE("embedded dex is a placeholder — nothing to load");
        return;
    }
    jobject byteBuf = env->NewDirectByteBuffer(
        const_cast<unsigned char *>(classes_dex),
        static_cast<jlong>(classes_dex_len));
    if (byteBuf == nullptr) {
        env->ExceptionClear();
        return;
    }
    jclass imdcl = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    jclass clCls = env->FindClass("java/lang/ClassLoader");
    if (imdcl == nullptr || clCls == nullptr) {
        env->ExceptionClear();
        return;
    }
    jmethodID imdclCtor = env->GetMethodID(imdcl, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    jmethodID loadClassMid = env->GetMethodID(clCls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (imdclCtor == nullptr || loadClassMid == nullptr) {
        env->ExceptionClear();
        return;
    }
    jobject loader = env->NewObject(imdcl, imdclCtor, byteBuf, nullptr);
    if (loader == nullptr) {
        env->ExceptionClear();
        return;
    }
    loader = env->NewGlobalRef(loader);

    auto loadClass = [&](const char *name) -> jclass {
        jstring s = env->NewStringUTF(name);
        jclass c = static_cast<jclass>(env->CallObjectMethod(loader, loadClassMid, s));
        env->DeleteLocalRef(s);
        if (c == nullptr) {
            env->ExceptionClear();
            LOGE("loadClass failed: %s", name);
            return nullptr;
        }
        return static_cast<jclass>(env->NewGlobalRef(c));
    };

    jclass bridgeCls = loadClass("com.phantom.hooker.HookBridge");
    jclass hookerCls = loadClass("com.phantom.hooker.PhantomHooker");
    if (bridgeCls == nullptr || hookerCls == nullptr) return;

    // ---- 3. register the config-feed natives and initialize Java state ----
    JNINativeMethod bridgeMethods[] = {
        {"nativeAddProp",       "(Ljava/lang/String;Ljava/lang/String;)V", (void *) nativeAddProp},
        {"nativeAddHiddenPath", "(Ljava/lang/String;)V",                   (void *) nativeAddHiddenPath},
    };
    if (env->RegisterNatives(bridgeCls, bridgeMethods, 2) != JNI_OK) {
        env->ExceptionClear();
        return;
    }

    jmethodID initMid = env->GetStaticMethodID(bridgeCls, "init",
        "(Ljava/lang/String;Ljava/lang/String;)V");
    if (initMid == nullptr) {
        env->ExceptionClear();
        return;
    }
    jstring jConfig = env->NewStringUTF(g_config.c_str());
    jstring jPkg = env->NewStringUTF(g_pkg);
    env->CallStaticVoidMethod(bridgeCls, initMid, jConfig, jPkg);
    env->DeleteLocalRef(jConfig);
    env->DeleteLocalRef(jPkg);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("HookBridge.init threw");
    }
    LOGI("config applied for %s", g_pkg);

    // ---- 4. apply LSPlant hooks ----
    jmethodID callbackMid = env->GetMethodID(hookerCls, "callback",
        "([Ljava/lang/Object;)Ljava/lang/Object;");
    if (callbackMid == nullptr) {
        env->ExceptionClear();
        return;
    }
    jobject callbackMethod = env->ToReflectedMethod(hookerCls, callbackMid, JNI_FALSE);
    callbackMethod = env->NewGlobalRef(callbackMethod);

    jfieldID backupFid = env->GetFieldID(hookerCls, "backup", "Ljava/lang/reflect/Method;");
    jfieldID hookIdFid = env->GetFieldID(hookerCls, "hookId", "I");
    if (backupFid == nullptr || hookIdFid == nullptr) {
        env->ExceptionClear();
        return;
    }

    static std::vector<std::pair<jclass, jobject>> keepAlive;
    int hooked = 0;
    for (const auto &def : HOOKS) {
        jclass cls = env->FindClass(def.cls);
        if (cls == nullptr) {
            env->ExceptionClear();
            continue;  // class not present (e.g. okhttp not bundled) — skip
        }
        jmethodID mid = def.isStatic
            ? env->GetStaticMethodID(cls, def.name, def.sig)
            : env->GetMethodID(cls, def.name, def.sig);
        if (mid == nullptr) {
            env->ExceptionClear();
            continue;  // method not present on this API level — skip
        }
        jobject target = env->ToReflectedMethod(cls, mid, def.isStatic ? JNI_TRUE : JNI_FALSE);
        jobject hooker = env->AllocObject(hookerCls);
        if (target == nullptr || hooker == nullptr) {
            env->ExceptionClear();
            continue;
        }
        env->SetIntField(hooker, hookIdFid, def.id);
        jobject backup = lsplant::Hook(env, target, hooker, callbackMethod);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (backup != nullptr) {
            env->SetObjectField(hooker, backupFid, backup);
            keepAlive.emplace_back(static_cast<jclass>(env->NewGlobalRef(cls)),
                                   env->NewGlobalRef(hooker));
            hooked++;
        }
        env->DeleteLocalRef(target);
        env->DeleteLocalRef(hooker);
        env->DeleteLocalRef(cls);
    }
    LOGI("phantom: %d/%zu methods hooked", hooked, sizeof(HOOKS) / sizeof(HOOKS[0]));

    // ---- 5. native libc hooks last (config maps are filled by now) ----
    hookLibcFunctions();
}

// ---------------------------------------------------------------------------
// Root companion — runs in a separate ROOT daemon (per ABI).
// Protocol:  <u32 pkgLen> <pkg>  →  <u32 cfgLen> <cfg bytes>   (cfgLen=0 = off)
// ---------------------------------------------------------------------------
static void companion_handler(int fd) {
    signal(SIGCHLD, SIG_IGN);  // reap launch-script children automatically

    uint32_t len = 0;
    if (!readFull(fd, &len, sizeof(len)) || len == 0 || len >= sizeof(g_pkg)) return;
    char pkg[256] = {0};
    if (!readFull(fd, pkg, len)) return;

    std::string base = "/data/adb/phantom";
    std::string marker = base + "/enabled/" + pkg;
    if (access(marker.c_str(), F_OK) != 0) {
        uint32_t zero = 0;
        writeFull(fd, &zero, sizeof(zero));
        return;
    }

    // Run this app's launch script in the background (root context).
    // Typical use: resetprop, mount tricks, custom prep commands.
    std::string script = base + "/scripts/" + pkg + ".sh";
    if (access(script.c_str(), F_OK) == 0) {
        pid_t pid = fork();
        if (pid == 0) {
            int devnull = open("/dev/null", O_WRONLY);
            if (devnull >= 0) {
                dup2(devnull, STDOUT_FILENO);
                dup2(devnull, STDERR_FILENO);
            }
            execl("/system/bin/sh", "sh", script.c_str(), static_cast<char *>(nullptr));
            _exit(127);
        }
    }

    std::string cfg = readFileAll((base + "/config.json").c_str());
    uint32_t n = static_cast<uint32_t>(cfg.size());
    if (!writeFull(fd, &n, sizeof(n))) return;
    if (n > 0) writeFull(fd, cfg.data(), n);
}

// ---------------------------------------------------------------------------
// Zygisk module
// ---------------------------------------------------------------------------
class PhantomModule : public ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        // ---- reset per-process state (this code runs in the forked child) ----
        g_config.clear();
        g_pkg[0] = '\0';
        {
            std::lock_guard<std::mutex> lk(g_mtx);
            g_props.clear();
            g_paths.clear();
        }
        g_hooks_done = false;

        if (args == nullptr) return;
        if (args->is_child_zygote != nullptr && *args->is_child_zygote) return;
        if (!args->nice_name) return;

        const char *pkg = env->GetStringUTFChars(args->nice_name, nullptr);
        if (pkg == nullptr) return;
        strncpy(g_pkg, pkg, sizeof(g_pkg) - 1);
        g_pkg[sizeof(g_pkg) - 1] = '\0';
        env->ReleaseStringUTFChars(args->nice_name, pkg);

        // Ask the root companion for this package's config.
        int fd = api->connectCompanion();
        if (fd < 0) return;
        uint32_t len = static_cast<uint32_t>(strlen(g_pkg));
        bool ok = writeFull(fd, &len, sizeof(len)) && writeFull(fd, g_pkg, len);
        uint32_t cfgLen = 0;
        if (ok && readFull(fd, &cfgLen, sizeof(cfgLen)) && cfgLen > 0 &&
            cfgLen < (32u << 20)) {
            g_config.resize(cfgLen);
            if (!readFull(fd, g_config.data(), cfgLen)) g_config.clear();
        }
        close(fd);

        if (!g_config.empty()) {
            // Belt & suspenders: unmount Magisk traces in this process even
            // when denylist enforcement is off.
            api->setOption(FORCE_DENYLIST_UNMOUNT);
        }
    }

    void postAppSpecialize(const AppSpecializeArgs *) override {
        if (g_config.empty()) return;
        setupHooks(env);
    }

private:
    Api *api = nullptr;
    JNIEnv *env = nullptr;
};

REGISTER_ZYGISK_MODULE(PhantomModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
