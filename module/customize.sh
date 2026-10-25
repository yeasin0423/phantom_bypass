#!/system/bin/sh
# Phantom Bypass — Magisk module installer script

SKIPUNZIP=0

uiPrint "- Phantom Bypass v1.0.0"

if [ "$API" -lt 28 ]; then
  abort "! Requires Android 9 (API 28) or newer"
fi

# zygisk libraries are picked up automatically from zygisk/<abi>.so
uiPrint "- Installing Zygisk libraries for $ARCH"

if ! grep -q "zygisk" /data/adb/magisk/util_functions.sh 2>/dev/null; then
  uiPrint "! Warning: this Magisk version may not include Zygisk. Enable Zygisk in Magisk settings!"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644

# configuration root used by the module + Phantom Manager app
mkdir -p /data/adb/phantom/enabled
mkdir -p /data/adb/phantom/scripts
chmod -R 755 /data/adb/phantom 2>/dev/null

uiPrint "- Install the Phantom Manager APK, open it, select target apps, then REBOOT."
