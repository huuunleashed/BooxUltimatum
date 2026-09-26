#!/bin/sh
# Starts (or restarts) the Shizuku server over adb. Needed after every tablet reboot.
# Runs the starter shipped inside the installed Shizuku app, so it always matches the installed version.
# Usage: ./tools/host/start-shizuku.sh [serial]
set -e
ADB="${ADB:-adb}"
[ -n "$1" ] && ADB="$ADB -s $1"
APK=$($ADB shell pm path moe.shizuku.privileged.api | head -n 1 | tr -d '\r')
[ -n "$APK" ] || { echo "Shizuku is not installed (moe.shizuku.privileged.api)." >&2; exit 1; }
DIR=$(echo "$APK" | sed -e 's/^package://' -e 's/base\.apk$//')
ABI=$($ADB shell "ls ${DIR}lib/" | head -n 1 | tr -d '\r')
$ADB shell "${DIR}lib/${ABI}/libshizuku.so"
sleep 2
if $ADB shell 'ps -A -o USER,PID,NAME' | grep -q shizuku_server; then
  echo "Shizuku server running."
else
  echo "Shizuku server did not start." >&2
  exit 1
fi
