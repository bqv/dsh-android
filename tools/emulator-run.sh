#!/bin/sh
# One headless Android emulator, in the foreground, for supervise-daemon.
#
# Why this exists: the emulator used to be launched as a child of whatever shell
# or agent job happened to start it, so it died with that job and the device
# silently disappeared mid-verification. Under the OpenRC user service in
# tools/emulator.initd it is started once, supervised, and reachable from any
# later shell.
#
# This deliberately does nothing else. Waiting for it to boot and reversing the
# host's carrier port into it are *transport* concerns, and exactly one process
# owns those: tools/adb-keepalive.sh, which is already blocked on adb's device
# event stream. Doing it here as well would mean a second waiter, a second poll
# loop and a race between the two.
set -u

EMU_HOME="${DSH_EMULATOR_HOME:-/home/user/var/work/android-x86-emulator}"
AVD="${DSH_EMULATOR_AVD:-x86native}"
PORT="${DSH_EMULATOR_PORT:-5554}"

export SDK="$EMU_HOME/sdk"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
export LD_LIBRARY_PATH="$EMU_HOME/libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# Pin one adb client for everything: two different platform-tools versions both
# want to own port 5037, and the resulting server restarts drop live transports.
ADB="${ADB:-/opt/android-sdk/platform-tools/adb}"
[ -x "$ADB" ] || ADB="$SDK/platform-tools/adb"
export PATH="$(dirname "$ADB"):$PATH"

# Modern adb no longer discovers emulator ports by scanning localhost, and this
# emulator's own bundled adb is a different version from the host's — a mismatch
# restarts the shared server and takes the just-registered transport with it. So
# the console is attached explicitly, which also pins the transport name for the
# keepalive (`127.0.0.1:<adb port>` matches its emulator pattern).
#
# PORT is the *console* port; the adb transport is the odd one beside it
# (5554 -> 5555). Attaching the console port yields a permanent `offline` entry.
#
# This wait is bounded and terminates the moment the transport opens: attaching a
# TCP port is the one thing adb cannot be asked to block on.
(
	until "$ADB" connect "127.0.0.1:$((PORT + 1))" 2>&1 | grep -q '^connected'; do
		sleep 2
	done
) &

exec "$SDK/emulator/emulator" "@$AVD" -no-window -no-audio -no-boot-anim -no-snapshot \
	-accel on -gpu swiftshader_indirect -memory 4096 -cores 4 -port "$PORT"
