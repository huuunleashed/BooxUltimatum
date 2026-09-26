#!/bin/sh
# One-time setup: grants BooxUltimatum the permissions of tier T1 over adb. They survive reboots.
# Usage: ./tools/host/grant-permissions.sh [serial]
set -e
ADB="${ADB:-adb}"
[ -n "$1" ] && ADB="$ADB -s $1"
PKG=app.booxultimatum
$ADB shell pm path "$PKG" >/dev/null || { echo "BooxUltimatum ($PKG) is not installed on the tablet." >&2; exit 1; }
for p in WRITE_SECURE_SETTINGS DUMP READ_LOGS; do
  $ADB shell pm grant "$PKG" "android.permission.$p"
  echo "Granted $p"
done
$ADB shell appops set "$PKG" GET_USAGE_STATS allow
echo "Allowed usage access"
echo "Done. These grants stay after a reboot; open BooxUltimatum > Access to check."
