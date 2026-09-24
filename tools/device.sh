#!/bin/sh
# Run a whole block of device interaction under one lease.
#
#   tools/device.sh 127.0.0.1:5555 .probe/verify.sh
#   tools/device.sh 127.0.0.1:5555 sh -c 'adb -s $SERIAL shell input tap 48 128'
#
# $SERIAL and $ADB are exported for the command.
#
# Why a lease over the whole block, and not a lock per adb command: a verification
# is a *sequence* - install, force-stop, launch, wait, tap, screencap - and a
# per-command lock lets another agent's step land between two of yours. Installing
# force-stops the app, so the other side's screenshots then show a cold start
# instead of the thing under test. That is not hypothetical: the parent's
# lineage-sheet check came back as a full-screen "Loading sessions..." because an
# agent installed a build mid-test.
#
# The lock lives in the checkout, never /tmp: each DSH bash call gets a private
# tmpfs, so a /tmp flock is not a mutex at all (see build.sh and tools/install.sh).
#
# One lock covers every device on purpose. The emulator is the only thing anyone
# should be driving, and the box cannot host a second one: 4 threads at a load
# average of ~14 while one emulator and a Gradle build are running.
set -e

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SERIAL="${1:?usage: tools/device.sh <serial> <command...>}"
shift
[ $# -gt 0 ] || { echo "tools/device.sh: no command given" >&2; exit 1; }

export SERIAL
export ADB="${ADB:-/opt/android-sdk/platform-tools/adb}"

# One lease per device, not one for the whole farm: the phone and the emulator are
# independent, and a single lock meant a long emulator sweep blocked a phone install
# outright — the parent waited out two timeouts on exactly that.
LOCK="$ROOT/.device-$(printf '%s' "$SERIAL" | tr -c 'A-Za-z0-9' '_').lock"
exec 9>"$LOCK"
if ! flock -n 9; then
	echo "device.sh: another block holds the lease for $SERIAL, waiting..." >&2
	flock 9
fi

exec "$@"
