#!/usr/bin/env node
// Mint the carrier cookie and speak one gateway RPC over loopback.
//
//   node tools/dsh-api.mjs <endpoint> ['{"arg":...}']
//   node tools/dsh-api.mjs --cookie          # print the Cookie header only
//   node tools/dsh-api.mjs --raw <endpoint>  # print the untouched response body
//
// Chain: nginx :80 -> gate 127.0.0.1:8080 -> carrier 127.0.0.1:8081. Loopback is
// in the gate's bypass list, so only the carrier cookie is needed.
import { createHmac, createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';

const PORT = Number(process.env.DSH_CARRIER_PORT ?? 8081);
const AUTHORITY = `127.0.0.1:${PORT}`;
const CRED = `${homedir()}/.dsh/.credentials.yaml`;

const b64url = (buf) =>
  Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

function secret() {
  const text = readFileSync(CRED, 'utf8');
  const at = text.indexOf('client-connection/browser-session');
  if (at < 0) throw new Error(`no browser-session record in ${CRED}`);
  const match = /secret:\s*(\S+)/.exec(text.slice(at));
  if (!match) throw new Error('no secret after browser-session record');
  return Buffer.from(match[1], 'base64url');
}

function cookie() {
  const now = Date.now();
  const body = b64url(
    JSON.stringify({
      version: 1,
      authority: AUTHORITY,
      issuedAt: now,
      expiresAt: now + 3650 * 24 * 3600 * 1000,
    }),
  );
  const mac = b64url(createHmac('sha256', secret()).update(body).digest());
  const name = `dsh-auth-${b64url(createHash('sha256').update(AUTHORITY).digest())}`;
  return `${name}=v1.${body}.${mac}`;
}

const argv = process.argv.slice(2);
if (argv[0] === '--cookie') {
  console.log(cookie());
  process.exit(0);
}

const raw = argv[0] === '--raw';
const endpoint = raw ? argv[1] : argv[0];
if (!endpoint) {
  console.error('usage: dsh-api.mjs [--raw] <endpoint> [argsJson]');
  process.exit(2);
}
const argsJson = raw ? argv[2] : argv[1];
const args = argsJson ? JSON.parse(argsJson) : {};
if (typeof args !== 'object' || Array.isArray(args)) throw new Error('args must be a JSON object');

const envelope = JSON.stringify({
  type: 'client-request',
  rpcId: `probe-${Date.now()}`,
  method: endpoint,
  payload: { args },
});

const response = await fetch(`http://127.0.0.1:${PORT}/api/${endpoint}`, {
  method: 'POST',
  headers: {
    host: AUTHORITY,
    cookie: cookie(),
    'content-type': 'application/json',
  },
  body: envelope,
});

const text = await response.text();
if (raw) {
  console.log(text);
} else {
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    console.error(text);
    process.exit(1);
  }
  const value = parsed?.result?.value;
  console.log(JSON.stringify(value === undefined ? parsed : value, null, 2));
}
if (!response.ok) process.exit(1);
