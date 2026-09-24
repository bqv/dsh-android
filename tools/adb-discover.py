#!/usr/bin/env python3
"""Discover a phone's Android wireless-debugging endpoint on the LAN.

Android randomises the adb port every time Wireless debugging is toggled, so
hard-coding it is hopeless. Wireless debugging advertises itself over mDNS as
`_adb-tls-connect._tcp` (and `_adb-tls-pairing._tcp` while the pairing dialog is
open), so this resolves those records directly with a hand-rolled DNS query — no
third-party modules.

Falls back to a TCP scan of the phone's address when multicast is unavailable
(which is common inside sandboxes), validating candidates with `adb connect`.

Prints one `host:port` per line, most likely first, and exits non-zero if nothing
was found.

    tools/adb-discover.py                # mDNS, then scan fallback
    tools/adb-discover.py --mdns-only
    tools/adb-discover.py --scan 192.168.1.100
"""

from __future__ import annotations

import argparse
import random
import re
import socket
import struct
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

MDNS_GROUP = "224.0.0.251"
MDNS_PORT = 5353
SERVICES = ("_adb-tls-connect._tcp.local", "_adb-tls-pairing._tcp.local")

# Android's wireless-debug ports live in the ephemeral range.
SCAN_RANGE = (30000, 50000)
# Ports that are definitely not adb, to keep the scan honest.
SCAN_EXCLUDE = {5037}


def encode_name(name: str) -> bytes:
    out = bytearray()
    for label in name.split("."):
        if not label:
            continue
        data = label.encode("utf-8")
        out.append(len(data))
        out += data
    out.append(0)
    return bytes(out)


def decode_name(packet: bytes, offset: int) -> tuple[str, int]:
    labels: list[str] = []
    jumped = False
    end = offset
    while True:
        if offset >= len(packet):
            break
        length = packet[offset]
        if length == 0:
            offset += 1
            if not jumped:
                end = offset
            break
        # Compression pointer.
        if length & 0xC0 == 0xC0:
            pointer = struct.unpack_from("!H", packet, offset)[0] & 0x3FFF
            if not jumped:
                end = offset + 2
            jumped = True
            offset = pointer
            continue
        offset += 1
        labels.append(packet[offset:offset + length].decode("utf-8", "replace"))
        offset += length
    return ".".join(labels), end


def parse_records(packet: bytes, results: dict[str, dict]) -> None:
    if len(packet) < 12:
        return
    qdcount, _, ancount, _ = struct.unpack_from("!HHHH", packet, 4)
    offset = 12
    for _ in range(qdcount):
        _, offset = decode_name(packet, offset)
        offset += 4
    for _ in range(ancount):
        name, offset = decode_name(packet, offset)
        if offset + 10 > len(packet):
            return
        rtype, _rclass, _ttl, rdlength = struct.unpack_from("!HHIH", packet, offset)
        offset += 10
        rdata = packet[offset:offset + rdlength]
        if rtype == 12:  # PTR
            target, _ = decode_name(packet, offset)
            for service in SERVICES:
                if name.lower() == service:
                    results.setdefault(target, {})["service"] = service
        elif rtype == 33 and rdlength >= 6:  # SRV
            port = struct.unpack_from("!H", rdata, 4)[0]
            target, _ = decode_name(packet, offset + 6)
            entry = results.setdefault(name, {})
            entry["port"] = port
            entry["target"] = target
        elif rtype == 1 and rdlength == 4:  # A
            results.setdefault(name, {})["address"] = socket.inet_ntoa(rdata)
        offset += rdlength


def mdns_lookup(timeout: float = 4.0) -> list[str]:
    query = bytearray(struct.pack("!HHHHHH", 0, 0, len(SERVICES), 0, 0, 0))
    for service in SERVICES:
        query += encode_name(service)
        query += struct.pack("!HH", 12, 1)  # PTR IN

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
        sock.settimeout(0.5)
        sock.sendto(bytes(query), (MDNS_GROUP, MDNS_PORT))
    except OSError as exc:
        print(f"mdns unavailable: {exc}", file=sys.stderr)
        sock.close()
        return []

    results: dict[str, dict] = {}
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            packet, _ = sock.recvfrom(9000)
        except socket.timeout:
            continue
        except OSError:
            break
        try:
            parse_records(packet, results)
        except Exception:  # malformed datagram; keep listening
            continue
    sock.close()

    found: list[str] = []
    for name, entry in results.items():
        if not name.lower().startswith("adb-"):
            continue
        port = entry.get("port")
        if not port:
            continue
        address = entry.get("address")
        if not address:
            target = entry.get("target", "")
            address = results.get(target, {}).get("address")
        if address:
            found.append(f"{address}:{port}")
    return sorted(set(found))


def port_open(host: str, port: int, timeout: float = 0.25) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        return sock.connect_ex((host, port)) == 0


def scan(host: str, low: int = SCAN_RANGE[0], high: int = SCAN_RANGE[1]) -> list[int]:
    ports = [p for p in range(low, high + 1) if p not in SCAN_EXCLUDE]
    random.shuffle(ports)  # spread the load; we only need the open ones
    open_ports: list[int] = []
    with ThreadPoolExecutor(max_workers=256) as pool:
        for port, is_open in zip(ports, pool.map(lambda p: port_open(host, p), ports)):
            if is_open:
                open_ports.append(port)
    return sorted(open_ports)


def mdns_endpoints() -> set[str]:
    """Endpoints mDNS already knows about, so they are never disconnected.

    mDNS and the scan are the same search by two routes, and when both work they
    disagree about what a not-yet-connected endpoint means: a port this host has a
    pairing key for is *authorised* but not connected, which `adb connect` reports
    as `offline` for a moment. Treating that as "not an adb transport" made the
    scan disconnect an endpoint the mDNS pass had just returned — which is how the
    phone's connect port got thrown away twice while pairing was being fixed.
    """
    try:
        return set(mdns_lookup())
    except Exception:
        return set()


def is_adb_device(endpoint: str, known: set[str] | None = None) -> bool:
    """True when the endpoint comes up as an adb device; cleans up on failure.

    A failed `adb connect` can still leave an `offline` transport registered, and
    a leftover transport makes every later bare `adb ...` fail with "more than one
    device", so losers are explicitly disconnected — except an endpoint mDNS
    vouched for, which is left alone to settle.
    """
    authorised = endpoint in (known or set())
    try:
        subprocess.run(["adb", "connect", endpoint], capture_output=True, timeout=15)
        out = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=15).stdout
        if re.search(rf"^{re.escape(endpoint)}\s+device", out, re.M):
            return True
        if re.search(rf"^{re.escape(endpoint)}\s+", out, re.M) and not authorised:
            subprocess.run(["adb", "disconnect", endpoint], capture_output=True, timeout=15)
    except Exception:
        if not authorised:
            subprocess.run(["adb", "disconnect", endpoint], capture_output=True)
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="192.168.1.100", help="phone IP for the scan fallback")
    parser.add_argument("--mdns-only", action="store_true")
    parser.add_argument("--scan", action="store_true", help="skip mDNS, go straight to scanning")
    args = parser.parse_args()

    if not args.scan:
        found = mdns_lookup()
        for endpoint in found:
            print(endpoint)
        if found or args.mdns_only:
            return 0 if found else 1

    print(f"scanning {args.host} for open ports…", file=sys.stderr)
    known = mdns_endpoints()
    for port in scan(args.host):
        endpoint = f"{args.host}:{port}"
        if is_adb_device(endpoint, known):
            print(endpoint)
            return 0
        print(f"  {endpoint} open but not an adb transport", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
