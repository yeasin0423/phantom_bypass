# Phantom Bypass

Per-app **root / emulator / SSL-pinning bypass** for Android 9–13, as a
**Zygisk module** (Magisk 26.1+) — a full port of the classic universal Frida
bypass script (originally by Daniele Linguaglossa, edits by Simone Quatrini)
into a native module that needs **no Frida, no PC, no cables** at runtime.

Everything is **per-app**: you pick which apps get hooked, and every part of
each app's fake identity is editable from the **Phantom Manager** companion
app.

---

## Feature map

| Requirement | Where it lives |
|---|---|
| Root hiding (su / Magisk / busybox / frida paths, binaries, packages) | Built-in lists + your extras (`extra_hide_files` / `extra_hide_apps` / global lists) |
| Emulator hiding (qemu / goldfish props & files, Build.* spoof) | `emu_hide` toggle + device profile |
| SSL unpinning (SSLContext, TrustManagerFactory, Conscrypt, OkHttp 2/3/4 — accept-all, no cert file needed) | `ssl_bypass` toggle |
| IMEI / IMSI / MEID / SIM serial / phone number / carrier / operator country | `telephony` profile section (TelephonyManager hooks) |
| Android ID | `telephony.android_id` |
| Custom per-app system props (fully editable) | `props` (Java `SystemProperties.get` + native `__system_property_get` hooks) |
| Global system props | `system.prop` (optional) + global props map |
| Hide more files / more apps (manual, fully configurable) | per-app + global editors |
| Run scripts for a target app when it launches | per-app `launch_scripts` (root, async, e.g. `resetprop ...`) |
| Boot-time su commands (`resetprop` etc.) | Global → boot script → `/data/adb/phantom/boot.sh` via `service.sh` |
| Command blocking (`which su`, `Runtime.exec`, `ProcessBuilder`) | built in; blocked commands return an empty "not found" process |
| Output filtering (`/proc/mounts` style reads containing magisk/frida/qemu…) | `BufferedReader.readLine` hook |
| All four ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` | built by CI, all shipped in one zip |
| Config UI with app picker | Phantom Manager APK |

## Architecture

```
Phantom Manager APK ──(su)──▶ /data/adb/phantom/
                                ├─ config.json          full config
                                ├─ enabled/<pkg>        marker per enabled app
                                ├─ scripts/<pkg>.sh     per-app launch scripts
                                └─ boot.sh              boot script (service.sh)

Zygisk (per app launch)
  preAppSpecialize ──▶ companion (root daemon): marker? → run script (async)
                                            └─▶ send config.json back
  postAppSpecialize ─▶ LSPlant init (Dobby inline hooks, ElfImg→libart)
                   ─▶ load embedded hooker DEX (com.phantom.hooker.*)
                   ─▶ apply 44 Java hooks   → HookBridge.dispatch()
                   ─▶ Dobby-hook libc: __system_property_get / openat /
                       fopen / faccessat  (native prop + file hiding)
```

Components:
- `zygisk/` — native module (`phantom.cpp`), LSPlant v1.0 + Dobby fetched by CMake, vendored `zygisk.hpp` (API v4) and SandHook `ElfImg`.
- `hooker/` — Java hook logic, compiled to a DEX at build time and embedded into the `.so` (`dex_bytes.h`).
- `app/` — Phantom Manager (Java, no external dependencies).
- `module/` — Magisk module skeleton (`module.prop`, `customize.sh`, `service.sh`, `action.sh`, …).
- `.github/workflows/build.yml` — builds everything in the cloud.

---

## 1. Create your GitHub repo & build

1. Create a (private) repo on github.com, e.g. `phantom-bypass`.
2. Upload the source (either drag-and-drop the folder contents via the web UI,
   or from a terminal):

   ```bash
   cd phantom-bypass
   git init
   git add .
   git commit -m "Phantom Bypass initial import"
   git branch -M main
   git remote add origin https://github.com/<your-user>/phantom-bypass.git
   git push -u origin main
   ```

3. GitHub Actions runs automatically on push (Actions tab → *Build Phantom
   Bypass*). You can also trigger it manually: **Actions → Build Phantom
   Bypass → Run workflow**.
4. When the run finishes, download the artifacts from the run page:
   - `Phantom-Bypass-Magisk-Module.zip`
   - `Phantom-Manager.apk`

   (Tag a release like `v1.0.0` and both files are attached to a GitHub
   Release automatically.)

> Build takes ~10–15 min (LSPlant + Dobby are fetched from GitHub; all four
> ABIs are built). If a build fails, check the step log — each ABI build
> prints its own `=== building <abi> ===` section.

### Build locally (optional)

Requirements: JDK 17, Android SDK (platform 34 + build-tools 34.0.0), NDK
r26b, CMake 3.18+, `xxd`.

```bash
javac -source 8 -target 8 -nowarn \
  -bootclasspath $ANDROID_HOME/platforms/android-34/android.jar \
  $(find hooker/src -name '*.java') -d build/hooker-classes
$ANDROID_HOME/build-tools/34.0.0/d8 --release --min-api 24 \
  --lib $ANDROID_HOME/platforms/android-34/android.jar \
  --output build/dex $(find build/hooker-classes -name '*.class')
xxd -i build/dex/classes.dex > zygisk/src/dex_bytes.h

for ABI in arm64-v8a armeabi-v7a x86_64 x86; do
  cmake -S zygisk -B build/$ABI \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_HOME/ndk/26.3.11579264/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release
  cmake --build build/$ABI -j4
done

gradle :app:assembleDebug
# assemble the zip: copy module/* + build/<abi>/libphantom.so → zygisk/<abi>.so
```

---

## 2. Install on the device

Prerequisites: **Magisk 26.1+** with **Zygisk enabled** (Magisk → Settings →
Zygisk). Android 9–13. Do **NOT** add your target apps to the Magisk
DenyList — the module does its own hiding, and denylisted processes don't get
modules injected.

1. Copy `Phantom-Bypass-Magisk-Module.zip` to the device.
2. Magisk app → **Modules → Install from storage** → pick the zip → reboot.
3. Install `Phantom-Manager.apk` (a normal debug-signed APK).
4. Open Phantom Manager → grant it **root** when Magisk asks.
5. Toggle the checkbox for every app you want bypassed. Long-press (or tap)
   an app to edit its profile.
6. Force-stop the target app and relaunch — hooks apply immediately on every
   new app start. (Only module *updates* need a reboot.)

## 3. Configure (Phantom Manager)

**Per-app profile** (long-press an app):

- **Bypass features** — root hide / emulator hide / SSL unpinning / telephony
  spoof toggles.
- **Device identity** — model, device, manufacturer, brand, product,
  hardware, board, fingerprint, build tags, build ID, Android version, SDK
  int. Empty = keep the real value. Defaults used when the profile is empty:
  the classic Samsung Galaxy S9 (`SM-G960F` / `starlte`) fingerprint.
- **Telephony / SIM / carrier** — Android ID, IMEI (both slots), MEID, IMSI,
  SIM serial, line number, voicemail, network operator (PLMN + name +
  country), SIM operator (PLMN + name + country), group id.
- **Custom system properties** — `key=value` lines, injected for
  `SystemProperties.get()` (Java) **and** native `__system_property_get()`.
- **Extra files / apps to hide** — one per line, extends the built-ins.
- **Launch scripts** — one command per line, executed **as root** every time
  the app starts (async, non-blocking). e.g. `resetprop ro.wifi.channels 11`.
  "Run launch scripts now" executes them on demand.

**Global settings** (applies to every enabled app): global props, global
files-to-hide, global apps-to-hide, and the **boot script** (run once as root
after boot by `service.sh` — ideal for global `resetprop` commands).

Config file (root-only): `/data/adb/phantom/config.json` — you can also edit
it directly with any root file manager; Phantom Manager re-reads it on open.

## 4. Verify / troubleshoot

```bash
adb logcat -s Phantom     # module logs: "N/44 methods hooked", config applied
ls /data/adb/phantom/enabled/     # marker files = apps that will be hooked
```

| Symptom | Fix |
|---|---|
| Manager says "Root required" | Grant su to Phantom Manager in Magisk → Superuser |
| `0/44 methods hooked` in logcat | Zygisk off, Magisk < 26.1, or app on DenyList → remove from DenyList |
| App still detects root | Add the probed path/package in the per-app or global lists; force-stop & relaunch |
| SSL still pinned | App uses a custom non-OkHttp stack — hooks cover SSLContext/Conscrypt/OkHttp/TrustManagerFactory; check `logcat -s Phantom` for the hook count |
| Props not applied | Props only exist inside the app process (per-app). For global props use `system.prop` or the boot script |
| Launch script didn't run | Some SELinux contexts block exec from the companion; use "Run launch scripts now" in the profile, or move the command to the boot script |
| Bootloop after install | Boot to safe mode / remove module via Magisk in recovery; module libs are ABI-named automatically, no manual steps needed |

## 5. Limitations

- `Build.MODEL`-style fields are spoofed by writing the static fields at
  process start (same technique as the Frida script's `.value =` writes).
- Apps that check signatures of *other* apps, or use their own native TLS
  stacks (BoringSSL directly), are out of scope for the universal accept-all
  approach.
- WebView SSL errors are not auto-proceeded (would require hooking every
  app's `WebViewClient` subclass).

## Credits & licenses

- Original Frida script concept: Daniele Linguaglossa / Simone Quatrini.
- [LSPlant](https://github.com/LSPosed/LSPlant) (v1.0) — Apache-2.0.
- [Dobby](https://github.com/jmpews/Dobby) — Apache-2.0.
- `zygisk.hpp` — Magisk (ISCL), vendored at API v4.
- `elf_util.*` — SandHook ElfImg by Swift Gan, via LSPosed (GPL-3.0).
- Everything else in this repo: do whatever you want, at your own risk. For
  authorized security research and penetration testing only.
