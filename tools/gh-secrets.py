#!/usr/bin/env python3
"""Set this repository's Actions secrets from a local keystore.

GitHub only accepts a secret value encrypted to the repository's ephemeral public
key with libsodium's sealed box (`crypto_box_seal`). Neither `gh` nor `pynacl` is
installed on this box, but libsodium itself is, so this calls it through ctypes:
the reference implementation rather than a hand-rolled one.

It was hand-rolled first, and that is worth recording. HSalsa20 and Salsa20 both
matched their published test vectors, and libsodium's own `crypto_box_beforenm`
agreed with the shared-key step exactly — so most of it was right — but the
Poly1305 input libsodium uses for a secretbox is not the bare ciphertext, and a
construction GitHub rejects with "improperly encrypted secret" is not worth the
confidence it appears to carry. Calling the library removes the whole question.

    GH_TOKEN=… tools/gh-secrets.py --keystore .secrets/release.keystore \\
        --password-file .secrets/keystore.pass --alias dsh

Only secret NAMES are printed; values never reach the log.
"""

from __future__ import annotations

import argparse
import base64
import ctypes
import ctypes.util
import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def load_sodium() -> ctypes.CDLL:
    """libsodium, or a clear refusal — there is no correct fallback."""
    name = ctypes.util.find_library("sodium") or ctypes.util.find_library("libsodium")
    if not name:
        sys.exit(
            "libsodium is not installed, and GitHub accepts a secret encrypted no "
            "other way. Install libsodium, or `pip install pynacl`.",
        )
    sodium = ctypes.CDLL(name)
    sodium.sodium_init()
    return sodium


def seal(sodium: ctypes.CDLL, public_key_b64: str, message: bytes) -> str:
    """libsodium `crypto_box_seal`: anonymous public-key encryption to a public key."""
    public_key = base64.b64decode(public_key_b64)
    if len(public_key) != 32:
        sys.exit(f"expected a 32-byte public key, got {len(public_key)}")
    length = len(message)
    out = ctypes.create_string_buffer(length + 48)
    rc = sodium.crypto_box_seal(
        out, message, ctypes.c_ulonglong(length), ctypes.c_char_p(public_key),
    )
    if rc != 0:
        sys.exit(f"crypto_box_seal failed with {rc}")
    return base64.b64encode(out.raw[: length + 48]).decode()


def request(path: str, method: str = "GET", body: dict | None = None) -> dict:
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        sys.exit("GH_TOKEN is not set")
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        API + path,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req) as response:
            payload = response.read()
    except urllib.error.HTTPError as error:
        sys.exit(f"{method} {path} -> {error.code} {error.read().decode(errors='replace')}")
    return json.loads(payload) if payload else {}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default="bqv/dsh-android")
    parser.add_argument("--keystore", required=True)
    parser.add_argument("--password-file", required=True)
    parser.add_argument("--alias", required=True)
    parser.add_argument(
        "--only",
        help="comma-separated subset of the secret names, to re-set one after a rotation",
    )
    args = parser.parse_args()

    sodium = load_sodium()
    password = open(args.password_file).read().strip()
    keystore = base64.b64encode(open(args.keystore, "rb").read()).decode()

    values = {
        "RELEASE_KEYSTORE_BASE64": keystore,
        "RELEASE_KEYSTORE_PASSWORD": password,
        "RELEASE_KEY_ALIAS": args.alias,
        "RELEASE_KEY_PASSWORD": password,
    }
    if args.only:
        wanted = {name.strip() for name in args.only.split(",") if name.strip()}
        unknown = wanted - values.keys()
        if unknown:
            sys.exit(f"not a secret this tool sets: {', '.join(sorted(unknown))}")
        values = {name: value for name, value in values.items() if name in wanted}

    key = request(f"/repos/{args.repo}/actions/secrets/public-key")
    for name, value in values.items():
        request(
            f"/repos/{args.repo}/actions/secrets/{name}",
            method="PUT",
            body={
                "encrypted_value": seal(sodium, key["key"], value.encode()),
                "key_id": key["key_id"],
            },
        )
        print(f"set {name}")

    listed = request(f"/repos/{args.repo}/actions/secrets")
    print("now present:", ", ".join(sorted(s["name"] for s in listed.get("secrets", []))))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
