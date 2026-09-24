#!/bin/sh
# Install the debug APK to one device, one install at a time.
#
#   tools/install.sh 127.0.0.1:5555          # emulator
#   tools/install.sh 192.168.1.100:38751     # phone
#   tools/install.sh <serial> <path-to.apk>
#
# Why a script: two agents installing at once to the same device collide, and the
# obvious guard - `flock /tmp/...` - does not work here, because each DSH bash
# call gets a private tmpfs and so a private /tmp. That is the same trap
# documented in build.sh; the lock lives in the checkout for the same reason.
set -e

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SERIAL="${1:?usage: tools/install.sh <serial> [apk]}"
APK="${2:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"

ADB="${ADB:-/opt/android-sdk/platform-tools/adb}"
[ -x "$ADB" ] || ADB=adb

[ -f "$APK" ] || { echo "no APK at $APK - build first" >&2; exit 1; }

LOCK="$ROOT/.install.lock"
exec 9>"$LOCK"
if ! flock -n 9; then
    echo "install.sh: another install holds $LOCK, waiting..." >&2
    flock 9
fi

echo "installing $(basename "$APK") -> $SERIAL"
"$ADB" -s "$SERIAL" install -r "$APK"
