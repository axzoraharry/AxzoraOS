#!/usr/bin/env node
/**
 * Axzora OS — family location sync server.
 *
 * Zero dependencies: uses only Node's built-in http module.
 * Holds each family group's most recent positions in memory and mirrors them
 * to a JSON file so a restart doesn't lose state.
 *
 * Endpoints
 *   GET  /health                     -> { ok, version, families, uptimeSec }
 *   POST /publish  {code,deviceId,name,lat,lon}
 *   GET  /members?code=XXXX          -> { members: [...] }
 *   POST /leave    {code,deviceId}   -> remove this device from the group
 *
 * Privacy: a device only ever appears after it explicitly publishes, and any
 * device can remove itself with /leave. Entries older than STALE_MS are dropped.
 */

const http = require('http');
const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = Number(process.env.PORT) || 8787;
const HOST = '0.0.0.0';
const STALE_MS = 24 * 60 * 60 * 1000; // forget a device after 24h of silence
const DB_FILE = path.join(__dirname, 'families.json');
const VERSION = '1.0';
const STARTED = Date.now();

/** @type {Record<string, Record<string, {name:string,lat:number,lon:number,updated:number}>>} */
let families = {};

// ---- persistence -----------------------------------------------------------

function load() {
  try {
    families = JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
    console.log(`[axzora] loaded ${Object.keys(families).length} family group(s)`);
  } catch {
    families = {};
  }
}

let saveTimer = null;
function saveSoon() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    fs.writeFile(DB_FILE, JSON.stringify(families), (err) => {
      if (err) console.error('[axzora] save failed:', err.message);
    });
  }, 1000);
}

// ---- chat / private-message / room persistence ------------------------------
// Group chats, private messages and live rooms are kept in memory for speed;
// they also mirror to disk so a restart doesn't lose conversations.
const STATE_FILE = path.join(__dirname, 'state.json');

function saveChatState() {
  const out = { chatSeq, dmSeq, roomSeq };
  out.chats = Object.fromEntries(chats);
  out.dms = Object.fromEntries(dms);
  out.dmReads = Object.fromEntries(dmReads);
  out.rooms = Object.fromEntries([...rooms].map(([id, r]) => [id, {
    roomId: r.roomId, title: r.title, host: r.host, hostName: r.hostName,
    live: !!r.live, ts: r.ts,
    invited: [...r.invited],
    members: [...r.members.values()].map((m) => ({ uid: m.uid, name: m.name, joined: m.joined })),
  }]));
  try { fs.writeFileSync(STATE_FILE, JSON.stringify(out)); } catch (e) { /* best-effort */ }
}

function loadChatState() {
  try {
    const j = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
    if (j.chatSeq) chatSeq = j.chatSeq;
    if (j.dmSeq) dmSeq = j.dmSeq;
    if (j.roomSeq) roomSeq = j.roomSeq;
    if (j.chats) for (const [k, v] of Object.entries(j.chats)) chats.set(k, v);
    if (j.dms) for (const [k, v] of Object.entries(j.dms)) dms.set(k, v);
    if (j.dmReads) for (const [k, v] of Object.entries(j.dmReads)) dmReads.set(k, v);
    if (j.rooms) for (const [k, r] of Object.entries(j.rooms)) rooms.set(k, {
      roomId: r.roomId, title: r.title, host: r.host, hostName: r.hostName,
      live: !!r.live, ts: r.ts,
      invited: new Set(r.invited || []),
      members: new Map((r.members || []).map((m) => [m.uid, { uid: m.uid, name: m.name, joined: m.joined }])),
    });
  } catch { /* first run: nothing on disk yet */ }
}

function pruneStale() {
  const cutoff = Date.now() - STALE_MS;
  for (const code of Object.keys(families)) {
    for (const id of Object.keys(families[code])) {
      if (families[code][id].updated < cutoff) delete families[code][id];
    }
    if (Object.keys(families[code]).length === 0) delete families[code];
  }
}

// ---- helpers ---------------------------------------------------------------

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(json),
    'Access-Control-Allow-Origin': '*',
    // x-axzora-token must be preflight-allowed, otherwise a browser cannot
    // send the HP authorisation header and every real payment fails CORS.
    'Access-Control-Allow-Headers': 'Content-Type, x-axzora-token',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  });
  res.end(json);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
      if (data.length > 8192) { reject(new Error('body too large')); req.destroy(); }
    });
    req.on('end', () => {
      try { resolve(data ? JSON.parse(data) : {}); }
      catch { reject(new Error('invalid JSON')); }
    });
    req.on('error', reject);
  });
}

/**
 * Family codes are matched case- and space-insensitively. Treating "Family"
 * and "family" as different groups split households in two with no error
 * shown — they simply never saw each other.
 */
function famKey(code) {
  return String(code || '').trim().toUpperCase();
}

const isNum = (v) => typeof v === 'number' && Number.isFinite(v);

// ---- Private chat relay ----------------------------------------------------
// The server relays ONLY ciphertext: messages are AES-GCM encrypted on the
// phones with a key derived from the family code, which the server never sees.
// Each group keeps a ring buffer of the last 200 messages in memory.

const chats = new Map(); // code -> [{id, from, cipher, iv, ts}]
let chatSeq = 1;

function chatSend(code, from, cipher, iv) {
  if (!chats.has(code)) chats.set(code, []);
  const list = chats.get(code);
  const msg = { id: chatSeq++, from: String(from).slice(0, 40), cipher, iv, ts: Date.now() };
  list.push(msg);
  if (list.length > 200) list.shift();
  saveChatState();
  return msg.id;
}

function chatPoll(code, sinceId) {
  const list = chats.get(code) || [];
  return list.filter((m) => m.id > sinceId);
}

// ---- Private messages (DMs) ------------------------------------------------
// One thread per pair of Axzora IDs. The server relays ciphertext and delivers
// a thread ONLY to its two participants — this is what makes a message
// private, unlike the family chat where everyone with the code reads along.
// In-memory ring per thread (same durability as the family chat).

const dms = new Map();        // pairKey -> [{id, from, to, fromName, cipher, iv, ts}]
const dmReads = new Map();    // uid + '|' + peer -> last read message id
let dmSeq = 1;

function dmKey(a, b) { return [String(a), String(b)].sort().join('|'); }

function dmSend(from, to, fromName, cipher, iv) {
  const key = dmKey(from, to);
  if (!dms.has(key)) dms.set(key, []);
  const list = dms.get(key);
  const msg = { id: dmSeq++, from: String(from), to: String(to),
    fromName: String(fromName || '').slice(0, 40), cipher, iv, ts: Date.now() };
  list.push(msg);
  if (list.length > 300) list.shift();
  saveChatState();
  return msg.id;
}

// Every thread this uid takes part in, newest first, with unread counts.
function dmThreads(uid) {
  const out = [];
  for (const [key, list] of dms) {
    const [a, b] = key.split('|');
    if (a !== uid && b !== uid) continue;
    if (!list.length) continue;
    const peer = a === uid ? b : a;
    const last = list[list.length - 1];
    const readUpTo = dmReads.get(uid + '|' + peer) || 0;
    const unread = list.filter((m) => m.to === uid && m.id > readUpTo).length;
    const peerName = (users[peer] && users[peer].name) ||
      [...list].reverse().find((m) => m.from === peer)?.fromName || peer.slice(0, 8);
    out.push({ peerUid: peer, peerName, lastTs: last.ts, lastFrom: last.from, unread });
  }
  out.sort((x, y) => y.lastTs - x.lastTs);
  return out;
}

// ---- Happy Paisa directory + transfers -------------------------------------
// Every install registers a unique Axzora ID. Transfers are queued against the
// recipient's ID and delivered the next time that device checks its inbox.

const HP_FILE = path.join(__dirname, 'wallet.json');
let users = {};     // uid -> {uid, name, seen}
let inbox = {};     // uid -> [{id, from, fromName, hp, note, ts}]
let transferSeq = 1;


/* --------------------------------------------------------------- assistant

   The phone's assistant can answer real questions instead of only matching
   rules. The key never leaves the server: the app posts here, we call the
   model, and only the reply text goes back to the phone.

   Set ANTHROPIC_API_KEY in the Render dashboard. Without it this endpoint
   reports that it is unconfigured and the app quietly falls back to its
   on-device rules, so the assistant still works offline either way. */

const AI_MODEL = 'claude-opus-5';

/* DeepSeek (Mr Happy's tool-calling brain). The key lives SERVER-SIDE only:
   deepseek-key.json in this folder (gitignored) is the source of truth, with
   DEEPSEEK_API_KEY as a fallback. The app never ships the key — it talks to
   /ai/deepseek and the server attaches the secret. */
function loadDeepSeekKey() {
  try {
    const j = JSON.parse(fs.readFileSync(path.join(__dirname, 'deepseek-key.json'), 'utf8'));
    if (j && typeof j.key === 'string' && j.key.trim()) return j.key.trim();
  } catch (e) { /* no file */ }
  return process.env.DEEPSEEK_API_KEY || '';
}
const DEEPSEEK_KEY = loadDeepSeekKey();

/* Gemini (Mr Happy's alternative brain). Same pattern as DeepSeek: the key lives
   SERVER-SIDE only in gemini-key.json (gitignored), GEMINI_API_KEY as fallback.
   If not configured, the model-aware /ai/deepseek route simply reports it. */
function loadGeminiKey() {
  try {
    const j = JSON.parse(fs.readFileSync(path.join(__dirname, 'gemini-key.json'), 'utf8'));
    if (j && typeof j.key === 'string' && j.key.trim()) return j.key.trim();
  } catch (e) { /* no file */ }
  return process.env.GEMINI_API_KEY || '';
}
const GEMINI_KEY = loadGeminiKey();


const AI_SYSTEM = [
  'ROLE: You are Axzora, the assistant built into the Axzora OS home screen on a phone.',
  'Warm, plain, direct voice.',
  '',
  'HARD RULES: Reply in one to three plain sentences; the screen is small.',
  'No markdown, no bullet lists, no headings. Answer in the language the user',
  'wrote in. If you do not know, say so plainly rather than guessing. You cannot',
  'act on the phone, only answer, so never claim to have done something.',
  '',
  'CONTEXT: The phone itself already handles these when asked, so point the user',
  'at the feature briefly instead of lecturing: opening apps, calling or video',
  'calling people, private one-to-one messages from People, the family group',
  'chat, sending Happy Paisa, the family map, alarms and timers, and remembering',
  'facts they tell you.',
  '',
  'Before answering, check: short, plain, right language, nothing invented.',
].join(' ');

function anthropic(payload) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(payload);
    const req = https.request({
      hostname: 'api.anthropic.com',
      path: '/v1/messages',
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'content-length': Buffer.byteLength(data),
        'x-api-key': process.env.ANTHROPIC_API_KEY || '',
        'anthropic-version': '2023-06-01',
      },
      timeout: 30000,
    }, (r) => {
      let out = '';
      r.on('data', (c) => { out += c; });
      r.on('end', () => {
        try { resolve({ status: r.statusCode, body: JSON.parse(out) }); }
        catch (e) { reject(new Error('bad response from model')); }
      });
    });
    req.on('timeout', () => { req.destroy(new Error('model timed out')); });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}


/* DeepSeek proxy: OpenAI-compatible /chat/completions. The phone sends the
   full body (model, messages, tools); the server injects its key and forwards
   to api.deepseek.com, then relays the JSON verbatim so the app's existing
   OpenAI parser works unchanged. The key never leaves the server. */
function deepseekProxy(body) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const req = https.request({
      hostname: 'api.deepseek.com',
      path: '/chat/completions',
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'content-length': Buffer.byteLength(data),
        'authorization': 'Bearer ' + DEEPSEEK_KEY,
      },
      timeout: 60000,
    }, (r) => {
      let out = '';
      r.on('data', (c) => { out += c; });
      r.on('end', () => {
        try { resolve({ status: r.statusCode, body: JSON.parse(out) }); }
        catch (e) { reject(new Error('bad response from deepseek')); }
      });
    });
    req.on('timeout', () => { req.destroy(new Error('deepseek timed out')); });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

/* Gemini proxy — OpenAI-compatible /chat/completions via the Gemini OpenAI
   endpoint (generativelanguage.googleapis.com/v1beta/openai). The server injects
   GEMINI_KEY so the phone never sees it. Used when the requested model is a
   gemini-* model. */
function geminiProxy(body) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const req = https.request({
      hostname: 'generativelanguage.googleapis.com',
      path: '/v1beta/openai/chat/completions',
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'content-length': Buffer.byteLength(data),
        'authorization': 'Bearer ' + GEMINI_KEY,
      },
      timeout: 60000,
    }, (r) => {
      let out = '';
      r.on('data', (c) => { out += c; });
      r.on('end', () => {
        try { resolve({ status: r.statusCode, body: JSON.parse(out) }); }
        catch (e) { reject(new Error('bad response from gemini')); }
      });
    });
    req.on('timeout', () => { req.destroy(new Error('gemini timed out')); });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}


/* ------------------------------------------------------------------- calls

   Two things decide whether a call feels like WhatsApp or like a toy.

   Speed: the app used to ask "anything for me?" every 3 seconds, so every
   step of the handshake — offer, answer, each ICE candidate — waited up to
   3s for the next ask. A dozen steps meant a slow, laggy connect. Now the
   request is *held open* until there is something to report, so each step is
   delivered the instant it exists.

   Reachability: two phones on mobile data are usually both behind carrier
   NAT, which STUN alone cannot punch through — that is the "connecting…"
   that never connects. A TURN server relays the media when a direct path is
   impossible. See /ice below. */

const waiters = new Map(); // uid -> [{res, timer}]

function callPayload(uid) {
  const incoming = [];
  const answers = [];
  const ended = [];
  for (const c of calls.values()) {
    if (c.to === uid && c.state === 'ringing') {
      // roomId travels with the call so the receiving phone can tell a leg of
      // a group it is already in - answer silently - from a fresh invitation
      // to join one, which has to ring.
      incoming.push({
        callId: c.callId, from: c.from, fromName: c.fromName, sdp: c.sdp,
        roomId: c.roomId || '',
      });
    }
    if (c.from === uid && c.state === 'answered' && c.answer) {
      answers.push({ callId: c.callId, sdp: c.answer });
    }
    if ((c.from === uid || c.to === uid) && c.state === 'ended') {
      ended.push({ callId: c.callId });
    }
  }
  // Drain candidates for every call this device is part of, whatever its
  // state. Keying off the ringing/answered lists misses the callee once it
  // has answered, and its candidates would then never be delivered.
  const ice = {};
  for (const c of calls.values()) {
    if (c.from !== uid && c.to !== uid) continue;
    const list = drainIce(c.callId, uid);
    if (list.length) ice[c.callId] = list;
  }
  // Rooms this phone has been asked into but has not joined yet.
  const roomInvites = [];
  for (const r of rooms.values()) {
    if (r.invited.has(uid) && !r.members.has(uid)) roomInvites.push(roomView(r));
  }
  return { ok: true, incoming, answers, ended, ice, roomInvites };
}

function hasNews(p) {
  return p.incoming.length || p.answers.length || p.ended.length ||
    (p.roomInvites && p.roomInvites.length) || Object.keys(p.ice).length;
}

/** Release anyone parked on /call/poll for these uids. */
function wake(...uids) {
  for (const uid of uids) {
    const list = waiters.get(String(uid || '').toUpperCase());
    if (!list || !list.length) continue;
    waiters.delete(String(uid).toUpperCase());
    for (const w of list) {
      clearTimeout(w.timer);
      const p = callPayload(String(uid).toUpperCase());
      try { send(w.res, 200, p); } catch (e) { /* client already gone */ }
    }
  }
}

function park(uid, res, ms) {
  const list = waiters.get(uid) || [];
  const entry = {
    res,
    timer: setTimeout(() => {
      const rest = (waiters.get(uid) || []).filter((w) => w !== entry);
      if (rest.length) waiters.set(uid, rest); else waiters.delete(uid);
      // Nothing happened — answer empty so the phone loops straight back.
      try { send(res, 200, callPayload(uid)); } catch (e) { /* gone */ }
    }, ms),
  };
  list.push(entry);
  waiters.set(uid, list);
  // If the phone hangs up the socket, stop holding a slot for it.
  res.on('close', () => {
    clearTimeout(entry.timer);
    const rest = (waiters.get(uid) || []).filter((w) => w !== entry);
    if (rest.length) waiters.set(uid, rest); else waiters.delete(uid);
  });
}

/* WebRTC needs somewhere to relay media when a direct path can't be found.
   Public STUN alone fails between two phones on mobile data, which is the
   common case. Set TURN_URL / TURN_USER / TURN_PASS to your own relay; the
   default is the Open Relay free service, which works but is shared. */
// A relay set from the Admin console (Relay tab). Held in memory — this host
// wipes its disk on every deploy — and re-pushed by the admin phone whenever
// the console opens, so it heals itself after a redeploy.
let turnOverride = null;   // { urls: [...], username, credential }

function iceServers() {
  const list = [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' },
  ];
  if (turnOverride && turnOverride.urls.length) {
    list.push({ urls: turnOverride.urls, username: turnOverride.username, credential: turnOverride.credential });
  } else if (process.env.TURN_URL) {
    list.push({
      urls: process.env.TURN_URL.split(',').map((u) => u.trim()).filter(Boolean),
      username: process.env.TURN_USER || '',
      credential: process.env.TURN_PASS || '',
    });
  }
  // No default relay any more: the Open Relay service died (its hostname no
  // longer resolves), and a dead relay in the list is worse than none - every
  // call waited on its timeouts before connecting. Until TURN_URL is set,
  // calls connect wherever STUN can find a path; where it can't, the app now
  // says so instead of failing mutely.
  return list;
}

function loadWallet() {
  try {
    const raw = JSON.parse(fs.readFileSync(HP_FILE, 'utf8'));
    users = raw.users || {};
    inbox = raw.inbox || {};
    transferSeq = raw.transferSeq || 1;
    console.log(`[axzora] wallet: ${Object.keys(users).length} user(s)`);
  } catch { /* first run */ }
}
function saveWallet() {
  try {
    fs.writeFileSync(HP_FILE, JSON.stringify({ users, inbox, transferSeq, stakes }, null, 2));
  } catch (e) { console.error('[axzora] wallet save failed:', e.message); }
}
loadWallet();

// ---- Happy Paisa staking ---------------------------------------------------
// 6% APR, 30-day lock (mirrors the HP DApp). A stake locks HP out of the
// spendable balance; rewards accrue daily and are paid when unstaked. Kept in
// wallet.json so it travels with the rest of the wallet state.
const STAKING_APR = 0.06;          // 6% per year
const STAKING_LOCK_DAYS = 30;      // lock period
const STAKING_DAILY = STAKING_APR / 365;
let stakes = {};                   // uid -> { hp, startedAt, reward }
function loadStakes() {
  try {
    const raw = JSON.parse(fs.readFileSync(HP_FILE, 'utf8'));
    stakes = raw.stakes || {};
  } catch { /* first run */ }
}
loadStakes();

/** Reward accrued on [stake] as of now (daily rate × elapsed days). */
function accruedReward(stake) {
  if (!stake) return 0;
  const days = (Date.now() - (stake.startedAt || Date.now())) / 86400000;
  return (stake.hp || 0) * STAKING_DAILY * Math.max(0, days);
}

// ---- Admin notifications ---------------------------------------------------
// The admin can push a notification to one Axzora user or to everyone who has
// installed the app. Queued per recipient so a phone that was off still gets
// it when it next checks in.

const NOTICE_FILE = path.join(__dirname, 'notices.json');
let notices = new Map();  // uid -> [{id, title, text, ts}]
let noticeSeq = 1;

function loadNotices() {
  try {
    const raw = JSON.parse(fs.readFileSync(NOTICE_FILE, 'utf8'));
    notices = new Map(Object.entries(raw.notices || {}));
    noticeSeq = raw.noticeSeq || 1;
  } catch { /* first run */ }
}
function saveNotices() {
  try {
    fs.writeFileSync(NOTICE_FILE, JSON.stringify({
      notices: Object.fromEntries(notices), noticeSeq,
    }));
  } catch (e) { console.error('[axzora] notices save failed:', e.message); }
}
loadNotices();

function queueNotice(uid, title, text) {
  if (!notices.has(uid)) notices.set(uid, []);
  const list = notices.get(uid);
  list.push({
    id: noticeSeq++,
    title: String(title || 'Axzora').slice(0, 60),
    text: String(text || '').slice(0, 300),
    ts: Date.now(),
  });
  // Anything a device never collected within a day is dropped, so an install
  // that goes silent doesn't accumulate an unbounded backlog.
  const cutoff = Date.now() - 24 * 60 * 60 * 1000;
  notices.set(uid, list.filter((n) => n.ts > cutoff).slice(-50));
  saveNotices();
}

// ---- Voice call signalling -------------------------------------------------
// The server only passes setup messages between the two phones: who is
// calling, the SDP descriptions, and the ICE candidates. Once the call is up
// the audio flows directly between the devices and never touches this process.

/*
 * Group calls.
 *
 * There is no media server here - a room is only a guest list. Everyone in a
 * room holds an ordinary one-to-one connection to everyone else, so the same
 * invite/answer/ICE machinery carries a group call with nothing new in the
 * media path.
 *
 * The rule that keeps it simple: a phone joining a room dials everyone
 * already in it, and nobody dials the newcomer. So each pair is set up
 * exactly once, by whichever of them arrived later, and two phones can never
 * ring each other at the same moment.
 *
 * Mesh cost grows with the square of the group, and every extra person is
 * another stream to encode and decode. Four is what a phone handles without
 * the picture turning to slush.
 */
const rooms = new Map();      // roomId -> {roomId, title, host, invited:Set, members:Map, ts}

/*
 * Every Axzora install, live.
 *
 * Keyed by the install's stable device id, not the account, because one
 * person can hold a phone and a tablet - "all my devices" means both. Each
 * device heartbeats every half-minute; anything not seen in ONLINE_WINDOW is
 * shown as offline rather than dropped, so a phone in a pocket still appears
 * in its owner's list.
 */
const devices = new Map(); // deviceId -> {deviceId, uid, name, model, battery, charging, seen}
const ONLINE_WINDOW_MS = 90 * 1000;
const ringRequests = new Set(); // deviceIds asked to ring themselves
let roomSeq = 1;
const ROOM_MAX = 4;
/** A live broadcast can hold far more watchers than an interactive call. */
const LIVE_MAX = 24;
/** A room nobody has touched for this long is finished. */
const ROOM_IDLE_MS = 6 * 60 * 60 * 1000;

function roomView(r) {
  return {
    roomId: r.roomId,
    title: r.title,
    host: r.host,
    hostName: r.hostName,
    members: [...r.members.values()].map((m) => ({ uid: m.uid, name: m.name })),
  };
}

const calls = new Map();      // callId -> {from, fromName, to, sdp, answer, state, ts}
const iceQueues = new Map();  // callId + ':' + uid -> [candidate]
let callSeq = 1;
/** How long a call rings before it counts as missed. */
const RING_TIMEOUT_MS = 45_000;

function iceKey(callId, uid) { return `${callId}:${uid}`; }

function pushIce(callId, forUid, candidate) {
  const k = iceKey(callId, forUid);
  if (!iceQueues.has(k)) iceQueues.set(k, []);
  iceQueues.get(k).push(candidate);
}

function drainIce(callId, uid) {
  const k = iceKey(callId, uid);
  const out = iceQueues.get(k) || [];
  iceQueues.set(k, []);
  return out;
}

/**
 * Ends calls that rang unanswered and drops finished ones.
 *
 * A ringing call that never times out stays at the head of the recipient's
 * queue forever and blocks every later call from that or any other caller.
 */
function sweepCalls() {
  const now = Date.now();
  for (const r of rooms.values()) {
    if (now - r.ts > ROOM_IDLE_MS) rooms.delete(r.roomId);
  }
  for (const c of calls.values()) {
    if (c.state === 'ringing' && now - c.ts > RING_TIMEOUT_MS) {
      c.state = 'ended';
      c.missed = true;
      console.log(`[axzora] call ${c.callId} missed (no answer)`);
    }
  }
  const cutoff = now - 5 * 60 * 1000;
  for (const [id, c] of calls) {
    if (c.ts < cutoff) {
      calls.delete(id);
      iceQueues.delete(iceKey(id, c.from));
      iceQueues.delete(iceKey(id, c.to));
    }
  }
}
setInterval(sweepCalls, 5_000).unref?.();

// ---- Happy Paisa ledger ----------------------------------------------------
// The server holds the authoritative balance. Phones keep a local copy for
// speed and offline use, but this is the record that settles disagreements —
// a reinstalled phone gets its balance back, and a coin cannot exist twice.

const LEDGER_FILE = path.join(__dirname, 'ledger.json');
let balances = {};   // uid -> HP (number)
let ledger = [];     // append-only entries
let ledgerSeq = 1;
/** Offline voucher nonces already settled, so a replay is caught. */
let spentNonces = {};

/* The admin code comes from the environment and from nowhere else.
 *
 * It used to live in admin-code.json next to this file, which meant the code
 * to the whole admin console was committed to git — and shipping a default in
 * the source meant a deployment that forgot to configure it was still open to
 * anyone who had read the repository. Both are gone: no file, no default.
 *
 * Unset is a configuration error, not an invitation. With no code, admin
 * endpoints answer 503 and nothing matches, so the console simply does not
 * open until the operator sets AXZORA_ADMIN_CODE. */
function loadAdminCode() {
  const raw = process.env.AXZORA_ADMIN_CODE;
  if (typeof raw === 'string' && raw.trim()) return raw.trim();
  return null;
}
const ADMIN_CODE = loadAdminCode();
const ADMIN_CONFIGURED = Boolean(ADMIN_CODE);
if (!ADMIN_CONFIGURED) {
  console.log('[axzora] ADMIN CODE NOT SET — the admin console is closed. Set AXZORA_ADMIN_CODE.');
} else {
  console.log(`[axzora] admin code: configured from AXZORA_ADMIN_CODE (${ADMIN_CODE.length} chars)`);
}

/** Sends an admin refusal. The HTTP status carries the number; the body does not repeat it. */
function sendAdminDenied(res, denied) {
  const { status, ...rest } = denied;
  return send(res, status, { ok: false, ...rest });
}

/** Explains why admin is unavailable, or null when it is available. */
function adminAvailability() {
  if (ADMIN_CONFIGURED) return null;
  return {
    status: 503,
    error: 'admin not configured',
    detail: 'This server has no AXZORA_ADMIN_CODE set, so no code can unlock the console.',
  };
}

/* Shared local auth token for money-moving ledger endpoints. A device on the LAN
 * can read balances/users, but can only TRANSFER / STAKE / UNSTAKE / MINT if it
 * presents this token (x-axzora-token header or ?token=). This is the defense
 * against a random/evil-twin device on the network draining or moving HP. The
 * phone's own apps hold the same token (from /data/.../home/axzora-token.json),
 * so they keep working over loopback/LAN. */
function loadLedgerToken() {
  try {
    const f = '/data/data/com.dsharnessmobile.shell/files/home/axzora-token.json';
    const j = JSON.parse(fs.readFileSync(f, 'utf8'));
    if (j && typeof j.token === 'string' && j.token.trim()) return j.token.trim();
  } catch (e) { /* no file */ }
  return process.env.AXZORA_LEDGER_TOKEN || '';
}
const LEDGER_TOKEN = loadLedgerToken();
function ledgerAuthed(req) {
  if (!LEDGER_TOKEN) return true; // no token configured -> legacy open (documented)
  const h = String(req.headers['x-axzora-token'] || req.headers['x-api-key'] || '');
  if (h && h === LEDGER_TOKEN) return true;
  try { const u = new URL(req.url, 'http://x'); if (u.searchParams.get('token') === LEDGER_TOKEN) return true; } catch (e) {}
  return false;
}

/* Admin access = the admin code, on up to ADMIN_MAX_DEVICES phones at once.
 *
 * Knowing the code is what makes you admin. A device that presents it takes
 * one of the seats; when all seats are held, a further device is refused
 * until one signs out or goes idle. Seats are remembered across restarts. */
const ADMIN_MAX_DEVICES = Number(process.env.AXZORA_ADMIN_MAX_DEVICES) || 2;
const ADMIN_SESSION_FILE = path.join(__dirname, 'admin_session.json');

/** @type {Array<{deviceId:string,name:string,claimedAt:number,lastSeen:number}>} */
let adminSessions = [];

try {
  const raw = JSON.parse(fs.readFileSync(ADMIN_SESSION_FILE, 'utf8'));
  if (Array.isArray(raw)) adminSessions = raw.filter((r) => r && r.deviceId);
  else if (raw && raw.deviceId) adminSessions = [raw];       // old single-seat file
} catch (e) { /* no sessions yet */ }

function saveAdminSessions() {
  try {
    fs.writeFileSync(ADMIN_SESSION_FILE, JSON.stringify(adminSessions));
  } catch (e) { console.log('[axzora] admin session save failed', e.message); }
}

/* A seat goes stale so a lost or wiped phone can't hold it forever. Any
   admin request from that device refreshes it. */
const ADMIN_IDLE_MS = 12 * 60 * 60 * 1000;

function pruneAdminSessions() {
  const cutoff = Date.now() - ADMIN_IDLE_MS;
  const before = adminSessions.length;
  adminSessions = adminSessions.filter((a) => (a.lastSeen || 0) >= cutoff);
  if (adminSessions.length !== before) saveAdminSessions();
}

function adminSeat(deviceId) {
  pruneAdminSessions();
  return adminSessions.find((a) => a.deviceId === deviceId) || null;
}

function codeMatches(code) {
  /* Fail closed: with no code configured, nothing matches — not even "".
   * Compared over digests so the comparison is the same length every time and
   * the time it takes says nothing about how much of the code was right. */
  if (!ADMIN_CONFIGURED) return false;
  const given = crypto.createHash('sha256').update(String(code || '').trim().toUpperCase()).digest();
  const want = crypto.createHash('sha256').update(ADMIN_CODE.trim().toUpperCase()).digest();
  return crypto.timingSafeEqual(given, want);
}

function seatHolders() {
  return adminSessions.map((a) => a.name || a.deviceId.slice(0, 6)).join(', ');
}

/**
 * Authorises an admin request: the code must match and this device must
 * hold a seat. Returns null when allowed, or {status, error} to send back.
 */
function adminGate(code, deviceId) {
  const down = adminAvailability();
  if (down) return down;
  if (!codeMatches(code)) return { status: 403, error: 'not an admin' };
  const dev = String(deviceId || '').trim();
  if (!dev) return { status: 403, error: 'device id required' };
  const seat = adminSeat(dev);
  if (!seat) {
    return { status: 409, error: 'admin not claimed on this device', holder: seatHolders() };
  }
  seat.lastSeen = Date.now();
  saveAdminSessions();
  return null;
}

/** Legacy shape kept for any caller that only checks the code. */
function isAdminCode(code) {
  return codeMatches(code);
}

function loadLedger() {
  try {
    const raw = JSON.parse(fs.readFileSync(LEDGER_FILE, 'utf8'));
    balances = raw.balances || {};
    ledger = raw.ledger || [];
    ledgerSeq = raw.ledgerSeq || 1;
    spentNonces = raw.spentNonces || {};
    console.log(`[axzora] ledger: ${Object.keys(balances).length} account(s), ${ledger.length} entries`);
  } catch { /* first run */ }
}
function saveLedger() {
  try {
    fs.writeFileSync(LEDGER_FILE, JSON.stringify(
      { balances, ledger, ledgerSeq, spentNonces }, null, 2));
  } catch (e) { console.error('[axzora] ledger save failed:', e.message); }
}
loadLedger();

function balanceOf(uid) {
  return Number(balances[uid] || 0);
}

/** Appends an entry and moves the balance. Amounts are signed. */
function post(uid, hp, kind, party, note, meta) {
  balances[uid] = Math.max(0, balanceOf(uid) + hp);
  ledger.push({
    id: ledgerSeq++,
    uid, hp, kind, party,
    note: String(note || '').slice(0, 80),
    ts: Date.now(),
    ...(meta || {}),
  });
  if (ledger.length > 20000) ledger = ledger.slice(-20000);
}

// ---- Axa Store -------------------------------------------------------------
// .axo packages live in ./store. Each is a ZIP holding axo.json + app.apk
// (see pack-axo.js). The catalog is rebuilt from the directory on request.

const STORE_DIR = path.join(__dirname, 'store');
// The over-the-air update channel. update.json says which build is current;
// the .apk beside it is what gets installed. Both live in the repo so they
// survive Render's ephemeral disk.
const UPDATE_DIR = path.join(__dirname, 'update');

/** Read axo.json out of a STORE-method .axo zip without external deps. */
function readAxoMeta(file) {
  const buf = fs.readFileSync(file);
  // End-of-central-directory: scan backwards for signature 0x06054b50.
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 65558; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('not a zip');
  let ptr = buf.readUInt32LE(eocd + 16); // central directory offset
  const count = buf.readUInt16LE(eocd + 10);
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(ptr) !== 0x02014b50) break;
    const compSize = buf.readUInt32LE(ptr + 20);
    const nameLen = buf.readUInt16LE(ptr + 28);
    const extraLen = buf.readUInt16LE(ptr + 30);
    const cmtLen = buf.readUInt16LE(ptr + 32);
    const localOff = buf.readUInt32LE(ptr + 42);
    const fname = buf.toString('utf8', ptr + 46, ptr + 46 + nameLen);
    if (fname === 'axo.json') {
      const lnameLen = buf.readUInt16LE(localOff + 26);
      const lextraLen = buf.readUInt16LE(localOff + 28);
      const dataStart = localOff + 30 + lnameLen + lextraLen;
      const method = buf.readUInt16LE(localOff + 8);
      const raw = buf.subarray(dataStart, dataStart + compSize);
      const json = method === 0 ? raw : zlibInflate(raw);
      return JSON.parse(json.toString('utf8'));
    }
    ptr += 46 + nameLen + extraLen + cmtLen;
  }
  throw new Error('axo.json missing');
}
const zlib = require('zlib');
function zlibInflate(raw) { return zlib.inflateRawSync(raw); }

function storeCatalog() {
  if (!fs.existsSync(STORE_DIR)) return [];
  return fs.readdirSync(STORE_DIR)
    .filter((f) => f.endsWith('.axo'))
    .map((f) => {
      try {
        const meta = readAxoMeta(path.join(STORE_DIR, f));
        const stat = fs.statSync(path.join(STORE_DIR, f));
        return { file: f, axoBytes: stat.size, ...meta };
      } catch (e) {
        console.error(`[axa-store] skipping ${f}: ${e.message}`);
        return null;
      }
    })
    .filter(Boolean);
}

// ---- request handling ------------------------------------------------------

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  if (req.method === 'OPTIONS') return send(res, 204, {});

  try {
    // ---- admin notifications ----

    /* Take the console. Succeeds only if nobody holds it, or this same
       device already does — never by stealing it from a live session. */
    if (url.pathname === '/admin/claim' && req.method === 'POST') {
      const b = await readBody(req);
      const down = adminAvailability();
      if (down) return sendAdminDenied(res, down);
      if (!codeMatches(b.adminCode)) {
        return send(res, 403, { ok: false, error: 'wrong code' });
      }
      const dev = String(b.deviceId || '').trim();
      if (!dev) return send(res, 400, { ok: false, error: 'device id required' });

      let seat = adminSeat(dev);
      if (!seat) {
        if (adminSessions.length >= ADMIN_MAX_DEVICES) {
          return send(res, 409, {
            ok: false,
            error: `admin is already open on ${ADMIN_MAX_DEVICES} devices`,
            holder: seatHolders(),
            since: Math.min(...adminSessions.map((a) => a.claimedAt || 0)),
          });
        }
        seat = { deviceId: dev, name: '', claimedAt: Date.now(), lastSeen: Date.now() };
        adminSessions.push(seat);
      }
      seat.name = String(b.name || '').slice(0, 40) || seat.name;
      seat.lastSeen = Date.now();
      saveAdminSessions();
      console.log(`[axzora] admin claimed by ${dev.slice(0, 8)} (${adminSessions.length}/${ADMIN_MAX_DEVICES})`);
      return send(res, 200, {
        ok: true, claimedAt: seat.claimedAt,
        devices: adminSessions.length, maxDevices: ADMIN_MAX_DEVICES,
      });
    }

    /* Give this device's seat back so another phone can take it. */
    if (url.pathname === '/admin/release' && req.method === 'POST') {
      const b = await readBody(req);
      const down = adminAvailability();
      if (down) return sendAdminDenied(res, down);
      if (!codeMatches(b.adminCode)) {
        return send(res, 403, { ok: false, error: 'wrong code' });
      }
      const dev = String(b.deviceId || '').trim();
      adminSessions = adminSessions.filter((a) => a.deviceId !== dev);
      saveAdminSessions();
      return send(res, 200, { ok: true, devices: adminSessions.length });
    }

    if (url.pathname === '/admin/notify' && req.method === 'POST') {
      const body = await readBody(req);
      const denied = adminGate(body.adminCode, body.deviceId);
      if (denied) return sendAdminDenied(res, denied);
      const text = String(body.text || '').trim();
      if (!text) return send(res, 400, { ok: false, error: 'text required' });

      const title = String(body.title || 'Axzora').trim();
      const target = String(body.target || 'ALL').trim().toUpperCase();

      let recipients;
      if (target === 'ALL') {
        recipients = Object.keys(users);
      } else {
        if (!users[target]) return send(res, 404, { ok: false, error: 'no such Axzora ID' });
        recipients = [target];
      }

      recipients.forEach((uid) => queueNotice(uid, title, text));
      console.log(`[axzora] admin notice -> ${recipients.length} user(s)`);
      return send(res, 200, { ok: true, delivered: recipients.length });
    }

    // Everything an admin needs in one call: who exists, what they hold, and
    // whether anything is still queued for them.
    if (url.pathname === '/admin/overview' && req.method === 'GET') {
      const denied = adminGate(
        url.searchParams.get('adminCode'),
        url.searchParams.get('deviceId'),
      );
      if (denied) return sendAdminDenied(res, denied);
      const people = Object.values(users)
        .sort((a, b) => (b.seen || 0) - (a.seen || 0))
        .map((u) => ({
          uid: u.uid,
          name: u.name,
          phone: u.phone || '',
          seen: u.seen || 0,
          balanceHp: balanceOf(u.uid),
          pendingNotices: (notices.get(u.uid) || []).length,
          pendingTransfers: (inbox[u.uid] || []).length,
        }));
      const totalHp = people.reduce((sum, p) => sum + p.balanceHp, 0);
      return send(res, 200, {
        ok: true,
        people,
        totals: {
          users: people.length,
          circles: Object.keys(families).length,
          happyPaisa: totalHp,
          ledgerEntries: ledger.length,
        },
      });
    }

    if (url.pathname === '/notify/poll' && req.method === 'GET') {
      const uid = String(url.searchParams.get('uid') || '').trim().toUpperCase();
      const since = Number(url.searchParams.get('since') || 0);
      if (!uid) return send(res, 400, { ok: false, error: 'uid required' });
      const list = (notices.get(uid) || []).filter((n) => n.id > since);
      return send(res, 200, { ok: true, notices: list });
    }

    // ---- voice calls ----

    if (url.pathname === '/call/invite' && req.method === 'POST') {
      const { from, fromName, to, sdp, roomId } = await readBody(req);
      const src = String(from || '').trim().toUpperCase();
      const dst = String(to || '').trim().toUpperCase();
      if (!src || !dst || !sdp) return send(res, 400, { ok: false, error: 'from, to and sdp required' });
      if (!users[dst]) return send(res, 404, { ok: false, error: 'no such Axzora ID' });

      const callId = `c${callSeq++}`;
      calls.set(callId, {
        callId, from: src, fromName: String(fromName || 'Someone').slice(0, 40),
        to: dst, sdp, answer: null, state: 'ringing', ts: Date.now(),
        roomId: String(roomId || ''),
      });
      console.log(`[axzora] call ${callId}: ${src} -> ${dst}`);
      // Ring the callee now rather than on its next poll.
      wake(dst);
      return send(res, 200, { ok: true, callId, toName: users[dst].name });
    }

    // One poll returns everything a device needs: calls ringing for it, an
    // answer to a call it placed, new ICE candidates, and hangups.
    if (url.pathname === '/call/poll' && req.method === 'GET') {
      const uid = (url.searchParams.get('uid') || '').trim().toUpperCase();
      if (!uid) return send(res, 400, { ok: false, error: 'uid required' });

      const payload = callPayload(uid);
      // Answer at once when there is news. Otherwise, if the caller asked to
      // wait, hold the connection rather than making it come back in 3s.
      const wait = Math.min(Number(url.searchParams.get('wait')) || 0, 30000);
      if (hasNews(payload) || wait <= 0) return send(res, 200, payload);
      return park(uid, res, wait);
    }

    if (url.pathname === '/ice' && req.method === 'GET') {
      const servers = iceServers();
      return send(res, 200, { ok: true, iceServers: servers, relay: servers.some((x) => String(x.urls).includes('turn')) });
    }

    // Admin: set (or clear) the TURN relay used for calls across networks.
    if (url.pathname === '/admin/ice' && req.method === 'POST') {
      const body = await readBody(req);
      const denied = adminGate(body.adminCode, body.deviceId);
      if (denied) return sendAdminDenied(res, denied);
      const urls = String(body.url || '').split(',').map((u) => u.trim()).filter((u) => /^turns?:/i.test(u));
      turnOverride = urls.length ? { urls, username: String(body.username || ''), credential: String(body.credential || '') } : null;
      console.log('[axzora] relay ' + (turnOverride ? 'set: ' + urls.join(',') : 'cleared'));
      return send(res, 200, { ok: true, relay: !!turnOverride, iceServers: iceServers() });
    }

    if (url.pathname === '/call/answer' && req.method === 'POST') {
      const { callId, sdp } = await readBody(req);
      const c = calls.get(String(callId));
      if (!c) return send(res, 404, { ok: false, error: 'call not found' });
      c.answer = sdp;
      c.state = 'answered';
      c.ts = Date.now();
      wake(c.from);
      return send(res, 200, { ok: true });
    }

    if (url.pathname === '/call/ice' && req.method === 'POST') {
      const { callId, from, candidate } = await readBody(req);
      const c = calls.get(String(callId));
      if (!c) return send(res, 404, { ok: false, error: 'call not found' });
      // Hand the candidate to whichever side did NOT send it.
      const target = String(from).toUpperCase() === c.from ? c.to : c.from;
      pushIce(c.callId, target, candidate);
      c.ts = Date.now();
      wake(target);
      return send(res, 200, { ok: true });
    }

    // ---- group calls ----

    if (url.pathname === '/call/room/create' && req.method === 'POST') {
      const body = await readBody(req);
      const host = String(body.from || '').trim().toUpperCase();
      if (!host) return send(res, 400, { ok: false, error: 'from required' });

      const invited = [...new Set(
        (Array.isArray(body.invite) ? body.invite : [])
          .map((u) => String(u || '').trim().toUpperCase())
          .filter((u) => u && u !== host),
      )];
      if (!invited.length) return send(res, 400, { ok: false, error: 'invite at least one person' });
      // +1 for the host: the cap is the size of the room, not of the guest list.
      if (invited.length + 1 > ROOM_MAX) {
        return send(res, 400, {
          ok: false,
          error: 'A group call holds ' + ROOM_MAX + ' people, including you.',
        });
      }
      const unknown = invited.filter((u) => !users[u]);
      if (unknown.length) {
        return send(res, 404, { ok: false, error: 'no such Axzora ID: ' + unknown.join(', ') });
      }

      const hostName = String(body.fromName || (users[host] && users[host].name) || 'Someone').slice(0, 40);
      const roomId = 'r' + (roomSeq++);
      const r = {
        roomId,
        title: String(body.title || 'Group call').slice(0, 60),
        host,
        hostName,
        invited: new Set(invited),
        // The host is in the room from the start, so the first person to
        // accept has someone to dial.
        members: new Map([[host, { uid: host, name: hostName, joined: Date.now() }]]),
        ts: Date.now(),
      };
      rooms.set(roomId, r);
      console.log('[axzora] room ' + roomId + ': ' + host + ' + ' + invited.join(', '));
      // Ring every guest now rather than on their next poll.
      wake(...invited);
      saveChatState();
      return send(res, 200, { ok: true, ...roomView(r) });
    }

    /*
     * Join, and find out who to dial.
     *
     * `peers` is deliberately the membership as it stood *before* this phone
     * was added: those are exactly the people it must call, and nobody in
     * that list will call back.
     */
    if (url.pathname === '/call/room/join' && req.method === 'POST') {
      const body = await readBody(req);
      const roomId = String(body.roomId || '').trim();
      const uid = String(body.uid || '').trim().toUpperCase();
      const r = rooms.get(roomId);
      if (!r) return send(res, 404, { ok: false, error: 'that group call has ended' });
      if (!uid) return send(res, 400, { ok: false, error: 'uid required' });
      const cap = r.live ? LIVE_MAX : ROOM_MAX;
      if (!r.live && !r.invited.has(uid) && r.host !== uid) {
        return send(res, 403, { ok: false, error: 'not invited to that group call' });
      }
      if (!r.members.has(uid) && r.members.size >= cap) {
        return send(res, 409, { ok: false, error: r.live ? 'live is full' : 'that group call is full' });
      }

      const peers = [...r.members.values()]
        .filter((m) => m.uid !== uid)
        .map((m) => ({ uid: m.uid, name: m.name }));

      r.members.set(uid, {
        uid,
        name: String(body.name || (users[uid] && users[uid].name) || 'Someone').slice(0, 40),
        joined: Date.now(),
      });
      r.ts = Date.now();
      console.log('[axzora] room ' + roomId + ': ' + uid + ' joined (' + r.members.size + ')');
      saveChatState();
      return send(res, 200, { ok: true, ...roomView(r), peers });
    }

    /*
     * Add someone to a call already under way.
     *
     * The WhatsApp shape: you are talking, you want one more person, and
     * nobody should have to hang up and start again. The new guest is added
     * to the invite list and woken; when they accept, join() hands them the
     * existing membership to dial, so they connect to everyone at once.
     */
    if (url.pathname === '/call/room/invite' && req.method === 'POST') {
      const body = await readBody(req);
      const r = rooms.get(String(body.roomId || '').trim());
      if (!r) return send(res, 404, { ok: false, error: 'that group call has ended' });

      const asker = String(body.from || '').trim().toUpperCase();
      if (!r.members.has(asker)) {
        return send(res, 403, { ok: false, error: 'only someone in the call can invite' });
      }

      const add = [...new Set(
        (Array.isArray(body.invite) ? body.invite : [])
          .map((u) => String(u || '').trim().toUpperCase())
          .filter((u) => u && !r.members.has(u)),
      )];
      if (!add.length) return send(res, 400, { ok: false, error: 'nobody new to add' });

      const unknown = add.filter((u) => !users[u]);
      if (unknown.length) {
        return send(res, 404, { ok: false, error: 'no such Axzora ID: ' + unknown.join(', ') });
      }
      if (r.members.size + add.length > ROOM_MAX) {
        return send(res, 400, {
          ok: false,
          error: 'A group call holds ' + ROOM_MAX + ' people.',
        });
      }

      add.forEach((u) => r.invited.add(u));
      r.ts = Date.now();
      console.log('[axzora] room ' + r.roomId + ': + ' + add.join(', '));
      // Ring the new guests now, and tell the room its roster grew.
      wake(...add, ...r.members.keys());
      return send(res, 200, { ok: true, ...roomView(r) });
    }

    if (url.pathname === '/call/room/leave' && req.method === 'POST') {
      const body = await readBody(req);
      const r = rooms.get(String(body.roomId || '').trim());
      const uid = String(body.uid || '').trim().toUpperCase();
      if (r) {
        r.members.delete(uid);
        r.invited.delete(uid);
        r.ts = Date.now();
        // The legs to this phone end on their own - each side hangs up its
        // own connection - so this only keeps the roster honest.
        if (!r.members.size) {
          rooms.delete(r.roomId);
          console.log('[axzora] room ' + r.roomId + ': empty, closed');
        } else {
          wake(...r.members.keys());
        }
      }
      return send(res, 200, { ok: true });
    }

    if (url.pathname === '/call/room' && req.method === 'GET') {
      const r = rooms.get(String(url.searchParams.get('roomId') || '').trim());
      if (!r) return send(res, 404, { ok: false, error: 'that group call has ended' });
      return send(res, 200, { ok: true, ...roomView(r) });
    }

    if (url.pathname === '/call/hangup' && req.method === 'POST') {
      const { callId } = await readBody(req);
      const c = calls.get(String(callId));
      if (c) {
        c.state = 'ended';
        c.ts = Date.now();
        // Stop the other phone ringing immediately.
        wake(c.from, c.to);
      }
      return send(res, 200, { ok: true });
    }

    // ---- ledger ----

    if (url.pathname === '/ledger/balance' && req.method === 'GET') {
      const uid = (url.searchParams.get('uid') || '').trim().toUpperCase();
      if (!uid) return send(res, 400, { ok: false, error: 'uid required' });
      return send(res, 200, { ok: true, uid, balanceHp: balanceOf(uid) });
    }

    if (url.pathname === '/ledger/history' && req.method === 'GET') {
      const uid = (url.searchParams.get('uid') || '').trim().toUpperCase();
      const rows = ledger.filter((e) => e.uid === uid).slice(-100).reverse();
      return send(res, 200, { ok: true, entries: rows, balanceHp: balanceOf(uid) });
    }

    // Atomic transfer: both sides move together or neither does.
    if (url.pathname === '/ledger/transfer' && req.method === 'POST') {
      if (!ledgerAuthed(req)) return send(res, 401, { ok: false, error: 'AUTH_REQUIRED — provide x-axzora-token to move HP' });
      const { from, to, hp, note } = await readBody(req);
      const src = String(from || '').trim().toUpperCase();
      const dst = String(to || '').trim().toUpperCase();
      const amount = Number(hp);

      if (!src || !dst) return send(res, 400, { ok: false, error: 'from and to required' });
      if (src === dst) return send(res, 400, { ok: false, error: 'cannot pay yourself' });
      if (!(amount > 0)) return send(res, 400, { ok: false, error: 'amount must be positive' });
      if (!users[dst]) return send(res, 404, { ok: false, error: 'no such Axzora ID' });
      if (balanceOf(src) + 1e-9 < amount) {
        return send(res, 409, { ok: false, error: 'insufficient balance', balanceHp: balanceOf(src) });
      }

      post(src, -amount, 'transfer_out', users[dst]?.name || dst, note);
      post(dst, amount, 'transfer_in', users[src]?.name || src, note);

      // Still queue a notification for the recipient's device.
      if (!inbox[dst]) inbox[dst] = [];
      inbox[dst].push({
        id: transferSeq++,
        from: src,
        fromName: users[src]?.name || 'Someone',
        hp: amount,
        note: String(note || '').slice(0, 80),
        ts: Date.now(),
        ledgered: true,
      });
      saveLedger();
      saveWallet();
      console.log(`[axzora] ledger ${amount} HP ${src} -> ${dst}`);
      return send(res, 200, {
        ok: true, balanceHp: balanceOf(src), toName: users[dst]?.name || dst,
      });
    }

    // Minting lives here, not in the app: an admin code shipped inside an APK
    // can be read by anyone who unpacks it, which would let every install
    // create its own money.
    if (url.pathname === '/ledger/mint' && req.method === 'POST') {
      const { uid, hp, adminCode, deviceId } = await readBody(req);
      const who = String(uid || '').trim().toUpperCase();
      const amount = Number(hp);
      // Minting money is the most dangerous call here, so it takes the full
      // gate: right code, and from the one device holding the console.
      const mintDenied = adminGate(adminCode, deviceId);
      if (mintDenied) return sendAdminDenied(res, mintDenied);
      if (!who || !(amount > 0)) return send(res, 400, { ok: false, error: 'uid and positive hp required' });
      post(who, amount, 'mint', 'Axzora Treasury', 'Minted');
      saveLedger();
      console.log(`[axzora] minted ${amount} HP -> ${who}`);
      return send(res, 200, { ok: true, balanceHp: balanceOf(who) });
    }

    // ---- staking (6% APR, 30-day lock, mirrors the HP DApp) ----

    if (url.pathname === '/ledger/staking' && req.method === 'GET') {
      const uid = (url.searchParams.get('uid') || '').trim().toUpperCase();
      if (!uid) return send(res, 400, { ok: false, error: 'uid required' });
      const stake = stakes[uid];
      const reward = accruedReward(stake);
      const lockMs = STAKING_LOCK_DAYS * 86400000;
      const started = stake ? stake.startedAt : 0;
      const unlockAt = started + lockMs;
      const locked = !!(stake && Date.now() < unlockAt);
      return send(res, 200, {
        ok: true,
        stakedHp: stake ? stake.hp : 0,
        rewardHp: reward,
        apr: STAKING_APR,
        lockDays: STAKING_LOCK_DAYS,
        unlockAt,
        locked,
        balanceHp: balanceOf(uid),
      });
    }

    if (url.pathname === '/ledger/stake' && req.method === 'POST') {
      if (!ledgerAuthed(req)) return send(res, 401, { ok: false, error: 'AUTH_REQUIRED — provide x-axzora-token to stake' });
      const { uid, hp } = await readBody(req);
      const who = String(uid || '').trim().toUpperCase();
      const amount = Number(hp);
      if (!who || !(amount > 0)) return send(res, 400, { ok: false, error: 'uid and positive hp required' });
      if (stakes[who]) return send(res, 409, { ok: false, error: 'already staked — unstake first' });
      if (balanceOf(who) + 1e-9 < amount) {
        return send(res, 409, { ok: false, error: 'insufficient balance', balanceHp: balanceOf(who) });
      }
      post(who, -amount, 'stake', 'Staking', `Locked ${amount} HP`);
      stakes[who] = { hp: amount, startedAt: Date.now(), reward: 0 };
      saveLedger();
      saveWallet();
      console.log(`[axzora] staked ${amount} HP -> ${who}`);
      return send(res, 200, {
        ok: true, stakedHp: amount, apr: STAKING_APR, lockDays: STAKING_LOCK_DAYS,
        balanceHp: balanceOf(who),
      });
    }

    if (url.pathname === '/ledger/unstake' && req.method === 'POST') {
      if (!ledgerAuthed(req)) return send(res, 401, { ok: false, error: 'AUTH_REQUIRED — provide x-axzora-token to unstake' });
      const { uid } = await readBody(req);
      const who = String(uid || '').trim().toUpperCase();
      const stake = stakes[who];
      if (!stake) return send(res, 404, { ok: false, error: 'no stake found' });
      const lockMs = STAKING_LOCK_DAYS * 86400000;
      const remaining = (stake.startedAt + lockMs) - Date.now();
      if (remaining > 0) {
        return send(res, 409, {
          ok: false, error: 'stake still locked',
          unlockAt: stake.startedAt + lockMs, locked: true,
        });
      }
      const reward = accruedReward(stake);
      const total = stake.hp + reward;
      post(who, total, 'unstake', 'Staking', `Returned ${stake.hp} HP + ${reward.toFixed(4)} reward`);
      delete stakes[who];
      saveLedger();
      saveWallet();
      console.log(`[axzora] unstaked ${total.toFixed(4)} HP <- ${who}`);
      return send(res, 200, { ok: true, returnedHp: total, rewardHp: reward, balanceHp: balanceOf(who) });
    }

    // Settling an offline voucher.
    //
    // SECURITY (fixed 2026-09-11): this endpoint used to credit the recipient
    // and debit NOBODY, with no token and no admin code — only a caller-supplied
    // nonce. Anyone could POST {"uid":<me>,"nonce":<random>,"hp":1000000} and
    // mint unlimited Happy Paisa, which made the x-axzora-token gate on
    // /ledger/transfer pointless. Verified exploitable: 0 -> 5 -> 10 HP with
    // two different nonces and no credentials.
    //
    // It is now a real double-entry settlement: the voucher must be backable by
    // a registered sender, whose balance is debited in the same atomic step that
    // credits the recipient. Creating money is not something a voucher may do —
    // that stays behind the admin-gated /ledger/mint.
    if (url.pathname === '/ledger/settle' && req.method === 'POST') {
      if (!ledgerAuthed(req)) {
        return send(res, 401, { ok: false, error: 'AUTH_REQUIRED — provide x-axzora-token to settle a voucher' });
      }
      const { uid, nonce, hp, from } = await readBody(req);
      const who = String(uid || '').trim().toUpperCase();
      const issuer = String(from || '').trim().toUpperCase();
      const key = String(nonce || '');
      const amount = Number(hp);
      if (!who || !key || !(amount > 0)) {
        return send(res, 400, { ok: false, error: 'uid, nonce and positive hp required' });
      }
      if (spentNonces[key]) {
        return send(res, 409, {
          ok: false, error: 'already settled',
          settledBy: spentNonces[key].uid, at: spentNonces[key].ts,
        });
      }
      // A voucher must name a real, funded issuer. An unknown or empty issuer
      // would mean the HP appears from nowhere, so it fails closed.
      if (!issuer || !users[issuer]) {
        return send(res, 400, {
          ok: false,
          error: 'voucher issuer (from) must be a registered Axzora ID — an unbacked voucher would create HP from nothing',
        });
      }
      if (issuer === who) {
        return send(res, 400, { ok: false, error: 'cannot settle a voucher to its own issuer' });
      }
      if (balanceOf(issuer) < amount) {
        return send(res, 409, {
          ok: false, error: 'issuer has insufficient balance to back this voucher',
          issuer, issuerBalanceHp: balanceOf(issuer),
        });
      }
      // Atomic double-entry: the issuer pays, the recipient is credited.
      post(issuer, -amount, 'voucher_out', users[who]?.name || who, `Voucher settled to ${who}`, { nonce: key });
      post(who, amount, 'offline_in', users[issuer]?.name || issuer, 'Voucher settled', { nonce: key });
      spentNonces[key] = { uid: who, ts: Date.now() };
      saveLedger();
      return send(res, 200, { ok: true, balanceHp: balanceOf(who), issuerBalanceHp: balanceOf(issuer) });
    }

    // Announce this device's Axzora ID so others can pay it by ID.
    /*
     * A device announcing it is alive: identity plus a snapshot of state.
     * Cheap and frequent, so the owner's device list is always current.
     */
    if (url.pathname === '/devices/heartbeat' && req.method === 'POST') {
      const b = await readBody(req);
      const deviceId = String(b.deviceId || '').trim();
      const uid = String(b.uid || '').trim().toUpperCase();
      if (!deviceId || !uid) return send(res, 400, { ok: false, error: 'deviceId and uid required' });
      devices.set(deviceId, {
        deviceId,
        uid,
        name: String(b.name || 'My device').slice(0, 40),
        model: String(b.model || '').slice(0, 40),
        battery: Number.isFinite(+b.battery) ? Math.round(+b.battery) : -1,
        charging: !!b.charging,
        seen: Date.now(),
      });
      // Tell the device whether someone asked it to ring, and clear the flag.
      const ring = ringRequests.delete(deviceId);
      return send(res, 200, { ok: true, ring });
    }

    /* Everything this account owns, newest-seen first, with live status. */
    if (url.pathname === '/devices/list' && req.method === 'GET') {
      const uid = (url.searchParams.get('uid') || '').trim().toUpperCase();
      if (!uid) return send(res, 400, { ok: false, error: 'uid required' });
      const now = Date.now();
      const mine = [...devices.values()]
        .filter((d) => d.uid === uid)
        .sort((a, b) => b.seen - a.seen)
        .map((d) => ({
          deviceId: d.deviceId,
          name: d.name,
          model: d.model,
          battery: d.battery,
          charging: d.charging,
          seen: d.seen,
          online: now - d.seen < ONLINE_WINDOW_MS,
        }));
      return send(res, 200, { ok: true, devices: mine });
    }

    /*
     * Ask one of your own devices to ring - the Find-My-Phone move.
     *
     * The target must belong to the same account, so you can only ring your
     * own hardware. The device itself decides to ring on its next heartbeat;
     * nothing is pushed onto it against its will.
     */
    if (url.pathname === '/devices/ring' && req.method === 'POST') {
      const b = await readBody(req);
      const uid = String(b.uid || '').trim().toUpperCase();
      const target = String(b.deviceId || '').trim();
      const d = devices.get(target);
      if (!d) return send(res, 404, { ok: false, error: 'no such device' });
      if (d.uid !== uid) return send(res, 403, { ok: false, error: 'not your device' });
      ringRequests.add(target);
      return send(res, 200, { ok: true });
    }

    if (url.pathname === '/wallet/register' && req.method === 'POST') {
      const { uid, name, phone } = await readBody(req);
      if (!uid) return send(res, 400, { ok: false, error: 'uid required' });
      users[uid] = {
        uid,
        name: String(name || 'Axzora user').slice(0, 40),
        phone: String(phone || '').slice(0, 20),
        seen: Date.now(),
      };
      saveWallet();
      return send(res, 200, { ok: true });
    }

    // Everyone who has installed Axzora, so a new user sees who they can
    // pay, message or call without typing an ID.
    if (url.pathname === '/wallet/directory' && req.method === 'GET') {
      const me = (url.searchParams.get('uid') || '').trim().toUpperCase();
      const people = Object.values(users)
        .filter((u) => u.uid !== me)
        .sort((a, b) => (b.seen || 0) - (a.seen || 0))
        .slice(0, 200)
        .map((u) => ({ uid: u.uid, name: u.name, phone: u.phone || '', seen: u.seen || 0 }));
      return send(res, 200, { ok: true, people });
    }

    // Confirm an ID exists before sending to it.
    if (url.pathname === '/wallet/lookup' && req.method === 'GET') {
      const uid = (url.searchParams.get('uid') || '').trim().toUpperCase();
      const u = users[uid];
      return send(res, 200, u ? { ok: true, found: true, name: u.name }
        : { ok: true, found: false });
    }

    if (url.pathname === '/wallet/send' && req.method === 'POST') {
      const { from, fromName, to, hp, note } = await readBody(req);
      const dest = String(to || '').trim().toUpperCase();
      const amount = Number(hp);
      if (!dest || !(amount > 0)) {
        return send(res, 400, { ok: false, error: 'to and positive hp required' });
      }
      if (!users[dest]) return send(res, 404, { ok: false, error: 'no such Axzora ID' });
      if (!inbox[dest]) inbox[dest] = [];
      inbox[dest].push({
        id: transferSeq++,
        from: from || 'unknown',
        fromName: String(fromName || 'Someone').slice(0, 40),
        hp: amount,
        note: String(note || '').slice(0, 80),
        ts: Date.now(),
      });
      saveWallet();
      console.log(`[axzora] queued ${amount} HP -> ${dest}`);
      return send(res, 200, { ok: true, toName: users[dest].name });
    }

    // Pull anything waiting, then acknowledge so it is delivered once only.
    if (url.pathname === '/wallet/inbox' && req.method === 'GET') {
      const uid = (url.searchParams.get('uid') || '').trim().toUpperCase();
      if (!uid) return send(res, 400, { ok: false, error: 'uid required' });
      if (users[uid]) { users[uid].seen = Date.now(); saveWallet(); }
      return send(res, 200, { ok: true, transfers: inbox[uid] || [] });
    }

    if (url.pathname === '/wallet/ack' && req.method === 'POST') {
      const { uid, ids } = await readBody(req);
      const key = String(uid || '').trim().toUpperCase();
      const claimed = new Set(Array.isArray(ids) ? ids : []);
      if (inbox[key]) {
        inbox[key] = inbox[key].filter((t) => !claimed.has(t.id));
        saveWallet();
      }
      return send(res, 200, { ok: true });
    }

    if (url.pathname === '/chat/send' && req.method === 'POST') {
      const body = await readBody(req);
      const { code, from, cipher, iv } = body;
      if (!code || !cipher || !iv) return send(res, 400, { ok: false, error: 'code, cipher, iv required' });
      const id = chatSend(String(code), from || 'anon', String(cipher), String(iv));
      return send(res, 200, { ok: true, id });
    }

    if (url.pathname === '/chat/poll' && req.method === 'GET') {
      const code = famKey(url.searchParams.get('code'));
      const since = Number(url.searchParams.get('since') || 0);
      if (!code) return send(res, 400, { ok: false, error: 'code required' });
      return send(res, 200, { ok: true, messages: chatPoll(code, since) });
    }

    // ---- private messages ----

    if (url.pathname === '/dm/send' && req.method === 'POST') {
      const { from, to, fromName, cipher, iv } = await readBody(req);
      if (!from || !to || !cipher || !iv) return send(res, 400, { ok: false, error: 'from, to, cipher, iv required' });
      const id = dmSend(from, to, fromName, String(cipher), String(iv));
      wake(String(to));   // an open long-poll learns about it instantly
      return send(res, 200, { ok: true, id });
    }

    // The thread between uid and peer. Only its two participants can ask for
    // it — anyone else gets an empty list, never the ciphertext.
    if (url.pathname === '/dm/poll' && req.method === 'GET') {
      const uid = String(url.searchParams.get('uid') || '');
      const peer = String(url.searchParams.get('peer') || '');
      const since = Number(url.searchParams.get('since') || 0);
      if (!uid || !peer) return send(res, 400, { ok: false, error: 'uid, peer required' });
      const list = (dms.get(dmKey(uid, peer)) || [])
        .filter((m) => m.id > since && (m.from === uid || m.to === uid));
      return send(res, 200, { ok: true, messages: list });
    }

    if (url.pathname === '/dm/threads' && req.method === 'GET') {
      const uid = String(url.searchParams.get('uid') || '');
      if (!uid) return send(res, 400, { ok: false, error: 'uid required' });
      return send(res, 200, { ok: true, threads: dmThreads(uid) });
    }

    if (url.pathname === '/dm/read' && req.method === 'POST') {
      const { uid, peer, upTo } = await readBody(req);
      if (!uid || !peer) return send(res, 400, { ok: false, error: 'uid, peer required' });
      const k = String(uid) + '|' + String(peer);
      dmReads.set(k, Math.max(dmReads.get(k) || 0, Number(upTo) || 0));
      return send(res, 200, { ok: true });
    }

    /*
     * Over-the-air updates.
     *
     * The app polls this on launch. If versionCode here is higher than the
     * one installed, the app offers to update - no more sending an APK to
     * everyone by hand for every change.
     */
    if (url.pathname === '/update/check' && req.method === 'GET') {
      try {
        const manifest = JSON.parse(
          fs.readFileSync(path.join(UPDATE_DIR, 'update.json'), 'utf8'),
        );
        return send(res, 200, { ok: true, ...manifest });
      } catch (e) {
        return send(res, 200, { ok: false, error: 'no update published' });
      }
    }

    if (url.pathname === '/update/download' && req.method === 'GET') {
      let manifest;
      try {
        manifest = JSON.parse(fs.readFileSync(path.join(UPDATE_DIR, 'update.json'), 'utf8'));
      } catch (e) {
        return send(res, 404, { ok: false, error: 'no update published' });
      }
      // Preferred path: the APK is hosted off-box (e.g. a GitHub Release
      // asset, which allows up to 2 GB — the repo itself caps files at
      // 100 MB). If update.json carries a "url", redirect the phone there so
      // the big binary never has to live in git or on the free Render disk.
      // The Android updater follows redirects (instanceFollowRedirects=true).
      const remote = String(manifest.url || '');
      if (/^https?:\/\//i.test(remote)) {
        console.log('[axzora] update redirect -> ' + remote);
        res.writeHead(302, {
          Location: remote,
          'Access-Control-Allow-Origin': '*',
        });
        return res.end();
      }
      const file = String(manifest.file || '');
      // Filename comes from our own manifest, but treat it as untrusted all
      // the same: no traversal, .apk only.
      if (file.includes('/') || file.includes(String.fromCharCode(92)) || !file.endsWith('.apk')) {
        return send(res, 400, { ok: false, error: 'bad update file' });
      }
      const full = path.join(UPDATE_DIR, file);
      if (!fs.existsSync(full)) return send(res, 404, { ok: false, error: 'update file missing' });
      const stat = fs.statSync(full);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Access-Control-Allow-Origin': '*',
      });
      fs.createReadStream(full).pipe(res);
      console.log('[axzora] update download ' + file);
      return;
    }

    if (url.pathname === '/store/catalog' && req.method === 'GET') {
      return send(res, 200, { ok: true, apps: storeCatalog() });
    }

    if (url.pathname.startsWith('/store/download/') && req.method === 'GET') {
      const fname = decodeURIComponent(url.pathname.slice('/store/download/'.length));
      // No path traversal, .axo only
      if (fname.includes('/') || fname.includes('\\') || !fname.endsWith('.axo')) {
        return send(res, 400, { ok: false, error: 'bad filename' });
      }
      const full = path.join(STORE_DIR, fname);
      if (!fs.existsSync(full)) return send(res, 404, { ok: false, error: 'not found' });
      const stat = fs.statSync(full);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.axzora.axo',
        'Content-Length': stat.size,
        'Access-Control-Allow-Origin': '*',
      });
      fs.createReadStream(full).pipe(res);
      console.log(`[axa-store] download ${fname}`);
      return;
    }

    if (url.pathname === '/health') {
      pruneStale();
      return send(res, 200, {
        ok: true,
        version: VERSION,
        families: Object.keys(families).length,
        uptimeSec: Math.round((Date.now() - STARTED) / 1000),
      });
    }

    // Live rooms anyone can watch. A "room" is the group-call guest list; the
    // /live/list view exposes the ones a host is still active in, so Axzora
    // installs can find and watch them (and tip the host).
    if (url.pathname === '/live/list' && req.method === 'GET') {
      const now = Date.now();
      const live = [];
      for (const r of rooms.values()) {
        if (now - r.ts > ROOM_IDLE_MS) continue;
        live.push({
          roomId: r.roomId,
          title: r.title,
          host: r.host,
          hostName: r.hostName,
          viewers: r.members.size,
        });
      }
      return send(res, 200, { ok: true, rooms: live });
    }

    // Start a public live broadcast. Unlike a group call there is no invite
    // list and nobody is rung: the room is open, shows up in /live/list, and
    // anyone can join it by tapping. The host is the only member to begin with.
    if (url.pathname === '/live/start' && req.method === 'POST') {
      const body = await readBody(req);
      const host = String(body.from || '').trim().toUpperCase();
      if (!host) return send(res, 400, { ok: false, error: 'from required' });
      if (!users[host]) return send(res, 404, { ok: false, error: 'no such Axzora ID' });
      const hostName = String(body.fromName || (users[host] && users[host].name) || 'Someone').slice(0, 40);
      const roomId = 'r' + (roomSeq++);
      const r = {
        roomId,
        title: String(body.title || 'Live').slice(0, 60),
        host,
        hostName,
        invited: new Set(),
        live: true,
        members: new Map([[host, { uid: host, name: hostName, joined: Date.now() }]]),
        ts: Date.now(),
      };
      rooms.set(roomId, r);
      console.log('[axzora] live ' + roomId + ': ' + host);
      saveChatState();
      return send(res, 200, { ok: true, ...roomView(r) });
    }

    if (url.pathname === '/ai/chat' && req.method === 'POST') {
      const b = await readBody(req);
      if (!process.env.ANTHROPIC_API_KEY) {
        // Not an error the user should see — the app falls back to its rules.
        return send(res, 200, { ok: false, error: 'ai_not_configured' });
      }

      // The phone sends the recent turns plus anything it has been asked to
      // remember, so replies stay in context without the server storing chats.
      const turns = Array.isArray(b.messages) ? b.messages.slice(-12) : [];
      const messages = turns
        .filter((m) => m && typeof m.text === 'string' && m.text.trim())
        .map((m) => ({
          role: m.role === 'assistant' ? 'assistant' : 'user',
          content: String(m.text).slice(0, 4000),
        }));
      if (!messages.length) return send(res, 400, { ok: false, error: 'no message' });
      if (messages[0].role !== 'user') messages.shift();
      if (!messages.length) return send(res, 400, { ok: false, error: 'no message' });

      let system = AI_SYSTEM;
      const who = String(b.userName || '').trim().slice(0, 40);
      if (who) system += ` The person you are talking to is called ${who}.`;
      const facts = String(b.memory || '').trim().slice(0, 2000);
      if (facts) system += ` Things they have asked you to remember: ${facts}`;

      try {
        const r = await anthropic({
          model: AI_MODEL,
          // Short on purpose: these are chat replies on a phone screen. The
          // ceiling still leaves room for the model to think before answering.
          max_tokens: 4096,
          output_config: { effort: 'low' },
          system,
          messages,
        });

        if (r.status !== 200) {
          const msg = (r.body && r.body.error && r.body.error.message) || 'model error';
          console.log(`[axzora] ai   status=${r.status} ${msg}`);
          return send(res, 200, { ok: false, error: 'ai_failed' });
        }
        if (r.body.stop_reason === 'refusal') {
          return send(res, 200, { ok: true, reply: "I can't help with that one." });
        }
        const reply = (r.body.content || [])
          .filter((c) => c.type === 'text')
          .map((c) => c.text)
          .join('')
          .trim();
        if (!reply) return send(res, 200, { ok: false, error: 'ai_empty' });
        console.log(`[axzora] ai   ok in=${r.body.usage?.input_tokens} out=${r.body.usage?.output_tokens}`);
        return send(res, 200, { ok: true, reply });
      } catch (e) {
        console.log(`[axzora] ai   failed ${e.message}`);
        return send(res, 200, { ok: false, error: 'ai_unreachable' });
      }
    }

    /* DeepSeek proxy for Mr Happy's tool-calling brain. The phone POSTs an
       OpenAI-compatible body; the server injects the key and relays the JSON. */
    if (url.pathname === '/ai/deepseek' && req.method === 'POST') {
      const b = await readBody(req);
      const model = String((b && b.model) || '').toLowerCase();
      const isGemini = model.includes('gemini');
      const key = isGemini ? GEMINI_KEY : DEEPSEEK_KEY;
      if (!key) {
        return send(res, 200, { ok: false, error: 'ai_not_configured' });
      }
      try {
        const r = isGemini ? await geminiProxy(b) : await deepseekProxy(b);
        if (r.status !== 200) {
          const msg = (r.body && r.body.error && r.body.error.message) || ('HTTP ' + r.status);
          console.log(`[axzora] ${isGemini?'gemini':'deepseek'} status=${r.status} ${msg}`);
          return send(res, 200, { ok: false, error: `${isGemini?'gemini':'deepseek'}_failed` });
        }
        console.log(`[axzora] ${isGemini?'gemini':'deepseek'} ok usage=${(r.body.usage && r.body.usage.total_tokens) || '?'}`);
        return send(res, 200, r.body);
      } catch (e) {
        console.log(`[axzora] ${isGemini?'gemini':'deepseek'} failed ${e.message}`);
        return send(res, 200, { ok: false, error: `${isGemini?'gemini':'deepseek'}_unreachable` });
      }
    }

    if (url.pathname === '/publish' && req.method === 'POST') {
      const b = await readBody(req);
      const code = famKey(b.code);
      const deviceId = String(b.deviceId || '').trim();
      if (!code) return send(res, 400, { ok: false, error: 'missing family code' });
      if (!deviceId) return send(res, 400, { ok: false, error: 'missing deviceId' });
      if (!isNum(b.lat) || !isNum(b.lon)) return send(res, 400, { ok: false, error: 'lat/lon must be numbers' });
      if (b.lat < -90 || b.lat > 90 || b.lon < -180 || b.lon > 180) {
        return send(res, 400, { ok: false, error: 'lat/lon out of range' });
      }

      families[code] ||= {};
      families[code][deviceId] = {
        name: String(b.name || 'Member').slice(0, 40),
        lat: b.lat,
        lon: b.lon,
        // The world map holds more than people now: a vehicle is the same
        // kind of live marker, just drawn as a car and pointed by heading.
        kind: b.kind === 'vehicle' ? 'vehicle' : 'person',
        heading: isNum(b.heading) ? b.heading : 0,
        speed: isNum(b.speed) ? b.speed : 0,
        updated: Date.now(),
      };
      saveSoon();
      console.log(`[axzora] publish  code=${code} device=${deviceId.slice(0, 8)} name=${families[code][deviceId].name}`);
      return send(res, 200, { ok: true });
    }

    if (url.pathname === '/members' && req.method === 'GET') {
      pruneStale();
      const code = String(url.searchParams.get('code') || '').trim();
      if (!code) return send(res, 400, { ok: false, error: 'missing family code' });
      const group = families[code] || {};
      const members = Object.entries(group).map(([deviceId, m]) => ({
        deviceId, name: m.name, lat: m.lat, lon: m.lon, updated: m.updated,
        kind: m.kind || 'person', heading: m.heading || 0, speed: m.speed || 0,
      }));
      return send(res, 200, { ok: true, members });
    }

    if (url.pathname === '/leave' && req.method === 'POST') {
      const b = await readBody(req);
      const code = famKey(b.code);
      const deviceId = String(b.deviceId || '').trim();
      if (families[code]) {
        delete families[code][deviceId];
        if (Object.keys(families[code]).length === 0) delete families[code];
        saveSoon();
        console.log(`[axzora] leave    code=${code} device=${deviceId.slice(0, 8)}`);
      }
      return send(res, 200, { ok: true });
    }

    return send(res, 404, { ok: false, error: 'not found' });
  } catch (err) {
    return send(res, 400, { ok: false, error: err.message });
  }
});

// ---- startup ---------------------------------------------------------------

function localAddresses() {
  const out = [];
  const nets = os.networkInterfaces();
  for (const [name, addrs] of Object.entries(nets)) {
    for (const a of addrs || []) {
      if (a.family === 'IPv4' && !a.internal && !a.address.startsWith('169.254.')) {
        out.push({ name, address: a.address });
      }
    }
  }
  return out;
}

load();
pruneStale();

// Online Ludo rooms ride on this same HTTP server as a WebSocket endpoint
// (/ludo). It is loaded defensively: if anything in the Ludo module fails,
// the core backend — calls, wallet, family sync — keeps serving untouched.
try {
  require('./ludo').attach(server);
} catch (e) {
  console.error('  [ludo] disabled:', e && e.message);
}
// Voice relay for calls that can't find a direct path (same defensive load).
try {
  require('./voice').attach(server);
} catch (e) {
  console.error('  [voice] disabled:', e && e.message);
}

// Bring back chats, private messages and live rooms from the last run.
loadChatState();
// Keep a periodic safety copy of that same state, in case a write is missed.
setInterval(saveChatState, 30_000);

server.listen(PORT, HOST, () => {
  console.log('');
  console.log('  Axzora family sync server');
  console.log(`  listening on port ${PORT}`);
  console.log('');
  console.log('  Put ONE of these URLs in the app (Settings -> Server URL):');
  for (const { name, address } of localAddresses()) {
    console.log(`    http://${address}:${PORT}      (${name})`);
  }
  console.log('');
  console.log('  Phones must be on the same network as this laptop.');
  console.log('  Ctrl+C to stop.');
  console.log('');
});

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    console.log('\n[axzora] shutting down');
    try { fs.writeFileSync(DB_FILE, JSON.stringify(families)); } catch {}
    process.exit(0);
  });
}
