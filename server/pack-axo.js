#!/usr/bin/env node
/**
 * pack-axo — convert an Android APK into an Axzora .axo package.
 *
 * .axo format v1: a ZIP archive containing
 *   axo.json   — {format, id, name, version, versionCode, description, sha256}
 *   app.apk    — the installable payload (Android can only execute APKs;
 *                .axo is Axzora's distribution wrapper around one)
 *
 * Usage:
 *   node pack-axo.js <input.apk> --id com.example.app --name "My App" \
 *        [--version 1.0] [--versionCode 1] [--desc "text"] [--out dir]
 *
 * Zero external dependencies: the ZIP is written with STORE (no compression),
 * which every unzip implementation (including Android's ZipInputStream) reads.
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');

function arg(name, dflt) {
  const i = process.argv.indexOf('--' + name);
  return i > -1 ? process.argv[i + 1] : dflt;
}

const apkPath = process.argv[2];
if (!apkPath || apkPath.startsWith('--')) {
  console.error('usage: node pack-axo.js <input.apk> --id <pkg> --name <label> [--version v] [--versionCode n] [--desc text] [--out dir]');
  process.exit(1);
}
const id = arg('id');
const name = arg('name');
if (!id || !name) { console.error('--id and --name are required'); process.exit(1); }

const apk = fs.readFileSync(apkPath);
const meta = {
  format: 'axo/1',
  id,
  name,
  version: arg('version', '1.0'),
  versionCode: Number(arg('versionCode', '1')),
  description: arg('desc', ''),
  sha256: crypto.createHash('sha256').update(apk).digest('hex'),
  sizeBytes: apk.length,
  packedAt: new Date().toISOString(),
};
const metaBuf = Buffer.from(JSON.stringify(meta, null, 2));

// ---- minimal ZIP writer (STORE method) ------------------------------------

const crcTable = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) c = crcTable[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function u16(v) { const b = Buffer.alloc(2); b.writeUInt16LE(v); return b; }
function u32(v) { const b = Buffer.alloc(4); b.writeUInt32LE(v >>> 0); return b; }

function buildZip(entries) {
  const parts = [];
  const central = [];
  let offset = 0;
  for (const { fname, data } of entries) {
    const nameBuf = Buffer.from(fname);
    const crc = crc32(data);
    const local = Buffer.concat([
      u32(0x04034b50), u16(20), u16(0), u16(0), u16(0), u16(0),
      u32(crc), u32(data.length), u32(data.length),
      u16(nameBuf.length), u16(0), nameBuf,
    ]);
    parts.push(local, data);
    central.push(Buffer.concat([
      u32(0x02014b50), u16(20), u16(20), u16(0), u16(0), u16(0), u16(0),
      u32(crc), u32(data.length), u32(data.length),
      u16(nameBuf.length), u16(0), u16(0), u16(0), u16(0), u32(0),
      u32(offset), nameBuf,
    ]));
    offset += local.length + data.length;
  }
  const centralBuf = Buffer.concat(central);
  const end = Buffer.concat([
    u32(0x06054b50), u16(0), u16(0),
    u16(entries.length), u16(entries.length),
    u32(centralBuf.length), u32(offset), u16(0),
  ]);
  return Buffer.concat([...parts, centralBuf, end]);
}

const outDir = arg('out', path.dirname(apkPath));
const outPath = path.join(outDir, `${id}-${meta.version}.axo`);
fs.writeFileSync(outPath, buildZip([
  { fname: 'axo.json', data: metaBuf },
  { fname: 'app.apk', data: apk },
]));
console.log(`packed: ${outPath}`);
console.log(`  ${meta.name} ${meta.version} (${(apk.length / 1048576).toFixed(1)} MB)`);
console.log(`  sha256 ${meta.sha256.slice(0, 16)}…`);
