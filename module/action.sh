#!/system/bin/sh
# Phantom Bypass — Magisk "Action" button:
# re-runs every enabled app's launch script + the boot script manually.
if [ -f /data/adb/phantom/boot.sh ]; then
  sh /data/adb/phantom/boot.sh
fi
if [ -d /data/adb/phantom/scripts ]; then
  for f in /data/adb/phantom/scripts/*.sh; do
    [ -f "$f" ] && sh "$f"
  done
fi
echo "Phantom scripts executed."
