#!/system/bin/sh
# Phantom Bypass — runs after boot.
# Executes the user-editable global boot script (resetprop etc.).
MODDIR=${0%/*}
(
  until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
  done
  if [ -f /data/adb/phantom/boot.sh ]; then
    sh /data/adb/phantom/boot.sh
  fi
) &
