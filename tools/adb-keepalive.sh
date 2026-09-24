#!/bin/sh
# Keep one adb server + the phone's wireless transport alive, discovering the
# endpoint automatically.
#
# Three problems this solves:
#
#  1. adb's server is normally a child of whatever shell started it, so it dies
#     with that shell and the transport drops. Run this as a supervised
#     long-lived process (the OpenRC user service in tools/adb.initd) and plain
#     `adb ...` works from anywhere, across agent sessions and reboots.
#
#  2. Android randomises the wireless-debug port every time Wireless debugging is
#     toggled, so a hard-coded port is always a guess. This asks
#     tools/adb-discover.py instead, and re-discovers whenever the transport drops.
#
#  3. The phone's transport can drop at any moment. This waits on
#     `adb track-devices`, which *streams* transport changes, so a drop is acted
#     on as it happens rather than being noticed up to a poll interval later.
#
# Emulator transports are deliberately out of scope: they are local, they register
# themselves, and disconnecting one mid-boot removes the device from
# `adb devices` for the rest of the boot. An earlier revision swept every
# non-`device` entry and spent a whole evening kicking emulator-5554.
#
# ADB_PHONE pins a known endpoint and skips discovery; leave it unset to discover.
set -u

ROOT="${DSH_ANDROID_ROOT:-/home/user/var/work/dsh-android}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ROOT/.toolchain/android-home}"
HOST="${ADB_HOST:-192.168.1.100}"
PINNED="${ADB_PHONE:-}"
DISCOVER="$ROOT/tools/adb-discover.py"

mkdir -p "$ANDROID_USER_HOME"

log() { echo "$(date '+%H:%M:%S') $*"; }

# Local, self-registering transports. Never ours to disconnect — but an emulator
# coming up *is* ours to finish: the DSH app inside it reaches the host carrier
# through a reversed port, and that reverse has to be re-applied after every
# emulator restart.
is_emulator() {
	case "$1" in
		emulator-*) return 0 ;;
		# The whole emulator port range, even *and* odd. Odd is the adb transport;
		# even is the console, which is not a transport but which adb lists as
		# `offline` once anything has connected to it.
		#
		# Exempting only the odd ones looked tidier and cost a disconnect loop: the
		# console entry came straight back, every device-snapshot event re-dropped
		# it, and the log took five disconnects in eight seconds while the
		# emulator's own helper commands kept regenerating the events. A lingering
		# offline entry is cosmetic; a loop is not.
		localhost:55[0-9][0-9]|127.0.0.1:55[0-9][0-9]) return 0 ;;
		*) return 1 ;;
	esac
}

reverse_carrier() {
	# tcp:8081 is the host's carrier port (nginx :80 -> gate :8080 -> carrier).
	adb -s "$1" reverse tcp:8081 tcp:8081 >/dev/null 2>&1 &&
		log "reversed tcp:8081 on $1"
}

current=""
# The emulator transport whose carrier port is currently reversed, so a snapshot
# that merely repeats an unchanged device list does not re-apply it.
reversed=""

connected() {
	[ -n "$current" ] || return 1
	adb devices 2>/dev/null | grep -q "^$current[[:space:]]*device"
}

# `track-devices` reports changes, not the current state, so whatever was already
# registered before the stream opened has to be picked up once here — including an
# emulator that booted while this service was restarting.
adopt_existing() {
	adb devices 2>/dev/null | awk 'NR>1 && $2 == "device" {print $1}' | while read -r serial; do
		[ -n "$serial" ] || continue
		if is_emulator "$serial"; then
			reverse_carrier "$serial"
		else
			current="$serial"
			log "already connected $current"
		fi
	done
}

# A failed connect can leave an `offline` transport registered, and a leftover
# transport breaks every later bare `adb ...` with "more than one device".
drop_stale_transports() {
	adb devices 2>/dev/null | awk 'NR>1 && $2 != "device" {print $1}' | while read -r endpoint; do
		[ -n "$endpoint" ] || continue
		is_emulator "$endpoint" && continue
		[ "$endpoint" = "$current" ] && continue
		adb disconnect "$endpoint" >/dev/null 2>&1
		log "dropped stale transport $endpoint"
	done
}

reconnect() {
	candidate="$PINNED"
	if [ -z "$candidate" ] && [ -x "$DISCOVER" ] && command -v python3 >/dev/null 2>&1; then
		candidate=$(python3 "$DISCOVER" --host "$HOST" 2>/dev/null | head -1)
	fi

	if [ -z "$candidate" ]; then
		# Nothing found: the phone is probably asleep, or Wireless debugging is
		# off. Back off so a full port scan does not spin.
		log "no endpoint found; retrying in 30s"
		sleep 30
		return 1
	fi

	adb connect "$candidate" >/dev/null 2>&1
	# `adb connect` returns before the transport has settled, so block on the
	# transport rather than polling `adb devices` until it appears.
	if timeout 15 adb -s "$candidate" wait-for-device >/dev/null 2>&1 &&
		adb devices 2>/dev/null | grep -q "^$candidate[[:space:]]*device"; then
		log "connected $candidate"
		drop_stale_transports
		return 0
	fi

	log "$candidate did not come up as a device"
	adb disconnect "$candidate" >/dev/null 2>&1
	return 1
}

# `adb track-devices` is not a stream of per-device deltas: after a 4-hex-digit
# length it writes the *entire* current device list, `serial<TAB>state` per line.
# So each read is one snapshot, and the phones/emulators in it are reconciled
# against what we last saw. (An earlier revision read the length as if it were its
# own line and then fed the leftover text to `cut`, which is how "16#0032127.0.0.1"
# ended up in the log as an arithmetic error.)
track_once() {
	# `dd bs=1` reads exactly the requested number of bytes, one syscall each, so
	# the header and its payload cannot desync. A shell `read -n` buffers, which
	# would steal payload bytes from the next `dd`.
	while :; do
		header=$(dd bs=1 count=4 2>/dev/null)
		[ ${#header} -eq 4 ] || break
		count=$((16#$header)) 2>/dev/null || break
		payload=$(dd bs=1 count="$count" 2>/dev/null)
		[ -n "$payload" ] && apply_snapshot "$payload"
	done < <(adb track-devices 2>/dev/null)
}

apply_snapshot() {
	seen=""
	# A here-document, not a pipe: the loop has to run in this shell so the
	# reconciliation below sees the values it collected.
	while IFS='	' read -r serial state; do
		[ -n "$serial" ] || continue

		if is_emulator "$serial"; then
			# An emulator registering is ours to finish, not ours to drop: the DSH
			# app inside it reaches the host carrier through a reversed port, which
			# has to be re-applied after every emulator restart.
			if [ "$state" = "device" ] && [ "$serial" != "$reversed" ]; then
				reverse_carrier "$serial" && reversed="$serial"
			fi
			continue
		fi

		[ "$state" = "device" ] && seen="$serial"
	done <<-EOF
	$1
	EOF

	# Only the wireless transport is ours to follow, and only its changes matter.
	if [ "$seen" != "$current" ]; then
		if [ -n "$seen" ]; then
			log "connected $seen"
		elif [ -n "$current" ]; then
			log "lost $current"
		fi
		current="$seen"
	fi
	[ -n "$current" ] && drop_stale_transports
	return 0
}

adb start-server >/dev/null 2>&1
log "adb server up (host=$HOST, pinned=${PINNED:-none})"

# `track-devices` reports changes, not the current state, so whatever was already
# connected before the stream opened has to be picked up once here.
adopt_existing
if ! connected; then
	reconnect
fi

while true; do
	track_once
	log "track-devices ended; reconnecting"
	current=""
	reconnect
	sleep 2
done
