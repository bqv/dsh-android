#!/bin/sh
# Build the DSH Android app.
#
# The toolchain (JDK 17 + Gradle 8.7) and the Gradle/SDK caches all live under
# .toolchain/ so nothing is written to $HOME and the build works inside a
# workspace-write sandbox. /opt/android-sdk supplies the platform and build-tools.
set -e

ROOT=$(cd "$(dirname "$0")" && pwd)

export JAVA_HOME="$ROOT/.toolchain/jdk-17.0.20.1+1"
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
# AGP wants to cache SDK manifests; $HOME/.config/.android is read-only here.
export ANDROID_USER_HOME="$ROOT/.toolchain/android-home"
# The Kotlin compile daemon writes its liveness marker under $XDG_DATA_HOME
# (~/.local/share/kotlin/daemon by default), which is outside the workspace and
# therefore read-only in this sandbox. When that write fails the client cannot
# reach the daemon and silently falls back to in-process compilation - which is
# where the "Daemon compilation failed: Could not connect to Kotlin compile
# daemon" line came from, and most of why every build took ~4 minutes. Pointing
# XDG_DATA_HOME into the checkout lets the daemon actually start.
export XDG_DATA_HOME="$ROOT/.toolchain/xdg-data"
export GRADLE_USER_HOME="$ROOT/.toolchain/gradle-home"
mkdir -p "$ANDROID_USER_HOME" "$XDG_DATA_HOME"

# One build at a time, across every shell and every agent.
#
# The lock has to live in the checkout, NOT in /tmp: each DSH bash call gets its
# own private tmpfs, so two agents both "holding" /tmp/dsh-build.lock were never
# actually meeting, and their Gradle/Kotlin daemons were free to overlap - which
# is how a build ended up failing to connect to the Kotlin compile daemon and
# another took 4 minutes instead of 2.
#
# The lock is taken here rather than by callers so it cannot be forgotten; the fd
# survives the `exec` below, so it is held for the whole build.
LOCK="$ROOT/.build.lock"
exec 9>"$LOCK"
if ! flock -n 9; then
    echo "build.sh: another build holds $LOCK, waiting..." >&2
    flock 9
fi

exec "$ROOT/.toolchain/gradle-8.7/bin/gradle" \
    -g "$GRADLE_USER_HOME" \
    --no-daemon \
    "$@"
