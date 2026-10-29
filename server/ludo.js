/*
 * Axzora online Ludo — authoritative room server, attached to the main
 * backend's HTTP server as a WebSocket endpoint at /ludo.
 *
 * DESIGN NOTES
 *  - Zero external dependencies. The main server is pure Node stdlib and also
 *    carries field-phone calls + the wallet; a missing npm package on Render
 *    would crash all of that. So the WebSocket protocol (RFC 6455) is spoken
 *    directly here, and server.js requires this module defensively — if any of
 *    it throws, Ludo simply goes dark and the rest of the backend is untouched.
 *  - The server owns the game: it seats players, rolls the dice, validates
 *    every move and resolves captures, so a tampered client cannot cheat the
 *    board. Happy Paisa is settled by each device's own wallet (debit at game
 *    start, winner credits the pot); the server only carries stake + pot so the
 *    numbers agree. Paid games are humans-only, so the pot always balances:
 *    every seat debits `stake`, the winner credits `stake * seats`.
 *  - "Classic 4 + full room of 8": a room seats up to 4 players and holds up to
 *    4 more as spectators, who watch live, chat, and are auto-seated into the
 *    empty seats on the next round (rematch).
 */
'use strict';

const crypto = require('crypto');

// ---- Ludo board geometry (identical to the client + old ludo-server) --------
const PATH = [
  [6,1],[6,2],[6,3],[6,4],[6,5],[5,6],[4,6],[3,6],[2,6],[1,6],[0,6],[0,7],
  [0,8],[1,8],[2,8],[3,8],[4,8],[5,8],[6,9],[6,10],[6,11],[6,12],[6,13],[6,14],
  [7,14],[8,14],[8,13],[8,12],[8,11],[8,10],[8,9],[9,8],[10,8],[11,8],[12,8],
  [13,8],[14,8],[14,7],[14,6],[13,6],[12,6],[11,6],[10,6],[9,6],[8,5],[8,4],
  [8,3],[8,2],[8,1],[8,0],[7,0],[6,0]
];
const SAFE  = new Set([0,8,13,21,26,34,39,47]);
const COLORS = ['r','g','y','b'];
const ENTRY  = [0,13,26,39];

// ============================ WebSocket (RFC 6455) ===========================
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

function accept(key) {
  return crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
}

const MAX_FRAME = 1 << 20;   // 1 MB — our JSON messages are tiny; cap abusive frames

// One framed connection over a raw TCP socket.
class Conn {
  constructor(socket, handlers) {
    this.socket = socket;
    this.buf = Buffer.alloc(0);
    this.alive = true;
    this.cid = crypto.randomBytes(6).toString('hex');
    // Handlers are wired BEFORE we start receiving, so a fast first message
    // can never be parsed and dropped before its handler exists.
    this.onmessage = (handlers && handlers.onmessage) || null;
    this.onbinary = (handlers && handlers.onbinary) || null;
    this.onclose = (handlers && handlers.onclose) || null;
    this.closed = false;
    this.room = null;
    this._frag = null;    // reassembly buffer for fragmented messages
    this._fragOp = 0;

    socket.on('data', (d) => this._feed(d));
    socket.on('close', () => this._dead());
    socket.on('error', () => this._dead());
    // An HTTP upgrade socket stays half-open after the peer's FIN, so 'close'
    // may never fire on an abrupt drop (lost network / terminate) — 'end' is
    // the signal that the client is gone. (Clean closes arrive as a WS close
    // frame in _feed; silent deaths are reaped by the heartbeat.)
    socket.on('end', () => this._dead());
  }

  _dead() {
    if (this.closed) return;
    this.closed = true;
    try { this.socket.destroy(); } catch (e) {}
    if (this.onclose) { try { this.onclose(); } catch (e) {} }
  }

  _feed(chunk) {
    this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk;
    // Parse as many complete frames as the buffer holds.
    for (;;) {
      const b = this.buf;
      if (b.length < 2) return;
      const opcode = b[0] & 0x0f;
      const masked = (b[1] & 0x80) !== 0;
      const fin = (b[0] & 0x80) !== 0;
      let len = b[1] & 0x7f;
      let off = 2;
      if (len === 126) { if (b.length < 4) return; len = b.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (b.length < 10) return; len = Number(b.readBigUInt64BE(2)); off = 10; }
      if (len > MAX_FRAME) return this._dead();          // bound memory / drop abuse
      let mask = null;
      if (masked) { if (b.length < off + 4) return; mask = b.slice(off, off + 4); off += 4; }
      if (b.length < off + len) return;                 // wait for the rest
      let payload = b.slice(off, off + len);
      if (masked) {
        const out = Buffer.allocUnsafe(len);
        for (let i = 0; i < len; i++) out[i] = payload[i] ^ mask[i & 3];
        payload = out;
      }
      this.buf = b.slice(off + len);

      if (opcode === 0x8) { return this._dead(); }              // close
      else if (opcode === 0x9) { this._frame(0xA, payload); }   // ping -> pong
      else if (opcode === 0xA) { this.alive = true; }           // pong
      else if (opcode === 0x1 || opcode === 0x2 || opcode === 0x0) {
        // Reassemble a (possibly fragmented) data message across frames.
        if (opcode !== 0x0) this._fragOp = opcode;              // first frame carries the type
        this._frag = this._frag ? Buffer.concat([this._frag, payload]) : payload;
        if (this._frag.length > MAX_FRAME) return this._dead();
        if (fin) {
          const whole = this._frag, op = this._fragOp;
          this._frag = null; this._fragOp = 0;
          if (op === 0x1 && this.onmessage) {
            try { this.onmessage(whole.toString('utf8')); } catch (e) {}
          } else if (op === 0x2 && this.onbinary) {
            try { this.onbinary(whole); } catch (e) {}
          }
        }
      }
    }
  }

  _frame(opcode, payload) {
    if (this.closed) return;
    payload = payload || Buffer.alloc(0);
    const len = payload.length;
    let header;
    if (len < 126) header = Buffer.from([0x80 | opcode, len]);
    else if (len < 65536) { header = Buffer.alloc(4); header[0] = 0x80 | opcode; header[1] = 126; header.writeUInt16BE(len, 2); }
    else { header = Buffer.alloc(10); header[0] = 0x80 | opcode; header[1] = 127; header.writeUInt32BE(0, 2); header.writeUInt32BE(len, 6); }
    try { this.socket.write(Buffer.concat([header, payload])); } catch (e) { this._dead(); }
  }

  send(obj) { this._frame(0x1, Buffer.from(JSON.stringify(obj))); }
  sendBinary(buf) { this._frame(0x2, buf); }
  ping() { this._frame(0x9, Buffer.alloc(0)); }
  close() { this._frame(0x8, Buffer.alloc(0)); this._dead(); }
}

// ================================ Ludo rooms =================================
//
// Identity: every phone sends a persistent `uid` (its wallet id). A seat is
// bound to the uid, not the socket, so a player who drops (network blip,
// accidental back, app killed) can RESUME into the same seat. On disconnect
// the seat is "away": its turns are auto-played for up to GRACE_MS; if the
// player hasn't returned by then, a paid seat forfeits (stake stays in the
// pot, seat is skipped) and a free seat keeps being auto-played.
//
// Friends only: online rooms never contain CPU seats. The host starts with
// whoever is there (1..4 seated; paid games need 2+). Each player picks a
// colour in the lobby; `p.seat` is the colour index (board geometry) and
// `room.turn` is an index into `room.players` (turn order = colour order).
//
// Money flags (`paid`, `settled`) live on the SERVER so a reconnecting client
// never debits or credits twice.

const GRACE_MS   = 120 * 1000;   // how long a dropped player can come back
const TURN_MS    = 30 * 1000;    // a connected player must act within this
const ROOM_TTL   = 30 * 60 * 1000;
const MAX_PLAYERS = 4, MAX_SPECT = 4;

const rooms = new Map();
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
function code4() {
  let c = '';
  for (let i = 0; i < 4; i++) c += ALPHABET[Math.floor(Math.random() * ALPHABET.length)];
  return rooms.has(c) ? code4() : c;
}

function newRoom(stake, isPublic) {
  return {
    code: code4(), players: [], spectators: [],
    turn: 0, dice: 0, phase: 'lobby', rolledSix: false, winner: -1,
    hostUid: null, stake: Math.max(0, stake | 0), pot: 0, public: !!isPublic,
    lastActive: Date.now(), gameNo: 0, rollNo: 0, turnDeadline: 0, turnTimer: null,
    sixRun: 0,          // consecutive sixes by the player on the clock
    quickAt: 0, quickTimer: null,   // quick-match rooms auto-start (CPU-filled if free)
    lastEvent: null,   // {kind:'capture'|'six'|'home'|'start'|'threesix', by, victim?} — seat (colour) indices
  };
}
function touch(room) { room.lastActive = Date.now(); }

function newPlayer(conn, uid, name, ai) {
  return {
    uid: ai ? ('ai-' + Math.random().toString(36).slice(2, 8)) : uid,
    conn, name: cleanName(name), seat: -1, color: null, ai: !!ai,
    toks: [-1, -1, -1, -1], done: false, connected: !ai,
    paid: false, settled: false, awayTimer: null,
  };
}
function cleanName(n) { return String(n || 'Player').replace(/[ -<>]/g, '').trim().slice(0, 14) || 'Player'; }

// ---- rules ------------------------------------------------------------------
function canMove(p, prog, dice) {
  if (prog >= 56) return false;
  if (prog < 0) return dice === 6;
  return prog + dice <= 56;
}
function movable(room, ti) {
  const p = room.players[ti];
  return p.toks.map((_, i) => i).filter((i) => canMove(p, p.toks[i], room.dice));
}
function loopIdx(seat, prog) { return (ENTRY[seat] + prog) % 52; }

function applyMove(room, ti, tok) {
  const p = room.players[ti];
  let prog = p.toks[tok];
  prog = prog < 0 ? 0 : prog + room.dice;
  p.toks[tok] = prog;
  room.lastEvent = null;
  if (prog === 56) room.lastEvent = { kind: 'home', by: p.seat };
  if (prog <= 50) {                                   // captures on the shared loop
    const idx = loopIdx(p.seat, prog);
    if (SAFE.has(idx)) room.lastEvent = { kind: 'safe', by: p.seat };   // landed on a star
    if (!SAFE.has(idx)) {
      const [r, c] = PATH[idx];
      room.players.forEach((op) => {
        if (op === p) return;
        // which of this opponent's tokens sit on the landing cell?
        const here = [];
        op.toks.forEach((o2, oti) => {
          if (o2 >= 0 && o2 <= 50) { const [orr, occ] = PATH[loopIdx(op.seat, o2)]; if (orr === r && occ === c) here.push(oti); }
        });
        // Two or more of the same colour together form a BLOCK and are safe.
        if (here.length === 1) { op.toks[here[0]] = -1; room.lastEvent = { kind: 'capture', by: p.seat, victim: op.seat }; }
        else if (here.length > 1 && !room.lastEvent) room.lastEvent = { kind: 'block', by: p.seat, victim: op.seat };
      });
    }
  }
}

function aiPick(room, ti, moves) {
  const p = room.players[ti];
  let best = moves[0], score = -1;
  for (const i of moves) {
    let sc = p.toks[i];
    const np = p.toks[i] < 0 ? 0 : p.toks[i] + room.dice;
    if (np <= 50) {
      const idx = loopIdx(p.seat, np);
      const [r, c] = PATH[idx];
      if (!SAFE.has(idx) && room.players.some((op) => op !== p &&
          op.toks.filter((t) => t >= 0 && t <= 50 &&
            PATH[loopIdx(op.seat, t)][0] === r && PATH[loopIdx(op.seat, t)][1] === c).length === 1)) sc += 100;   // a lone token (blocks are safe)
    }
    if (p.toks[i] < 0) sc += 20;
    if (np === 56) sc += 60;
    if (sc > score) { score = sc; best = i; }
  }
  return best;
}

// ---- turn flow --------------------------------------------------------------
function autoSeat(p) { return p.ai || !p.connected; }   // CPUs and away players are auto-played
function clearTurnTimer(room) { if (room.turnTimer) { clearTimeout(room.turnTimer); room.turnTimer = null; } room.turnDeadline = 0; }

// A connected human must act within TURN_MS, otherwise the server acts for them.
function armTurnTimer(room) {
  clearTurnTimer(room);
  const p = room.players[room.turn];
  if (!p || autoSeat(p)) return;
  if (room.phase !== 'await_roll' && room.phase !== 'await_move') return;
  room.turnDeadline = Date.now() + TURN_MS;
  room.turnTimer = setTimeout(() => {
    room.turnTimer = null;
    if (room.phase === 'await_roll') doRoll(room, room.turn, true);
    else if (room.phase === 'await_move') { const mv = movable(room, room.turn); if (mv.length) doMove(room, room.turn, aiPick(room, room.turn, mv)); }
  }, TURN_MS);
}

function nextTurn(room) {
  room.rolledSix = false; room.dice = 0; room.phase = 'await_roll'; room.sixRun = 0;
  let guard = 0;
  do { room.turn = (room.turn + 1) % room.players.length; guard++; }
  while (room.players[room.turn] && room.players[room.turn].done && guard <= room.players.length);
  if (lastStanding(room)) return;
  armTurnTimer(room);
  scheduleAuto(room);
}

// Everyone else forfeited a paid game: the last paid seat standing takes the
// pot. (Free games just keep going.) Returns true when the game ended.
function lastStanding(room) {
  if (room.phase === 'over' || room.phase === 'lobby') return false;
  if (room.players.every((p) => p.done)) { room.phase = 'over'; clearTurnTimer(room); return true; }
  if (room.stake > 0 && room.players.length > 1 && room.players.filter((p) => !p.done).length === 1) {
    const w = room.players.findIndex((p) => !p.done);
    room.players[w].done = true; room.winner = w; room.phase = 'over'; clearTurnTimer(room); return true;
  }
  return false;
}

function checkWin(room, ti) {
  const p = room.players[ti];
  if (p.toks.every((t) => t >= 56)) {
    p.done = true; room.winner = ti; room.phase = 'over';
    clearTurnTimer(room);
    return true;
  }
  return false;
}

function doRoll(room, ti, forced) {
  if (room.phase !== 'await_roll' || room.turn !== ti) return;
  touch(room);
  clearTurnTimer(room);
  room.dice = 1 + Math.floor(Math.random() * 6);
  room.rollNo++;
  room.rolledSix = (room.dice === 6);
  const p = room.players[ti];
  room.sixRun = room.rolledSix ? room.sixRun + 1 : 0;
  room.lastEvent = room.rolledSix ? { kind: 'six', by: p.seat } : null;
  if (room.sixRun >= 3) {
    // Three sixes in a row: the turn is lost and the third six is not played.
    room.lastEvent = { kind: 'threesix', by: p.seat };
    room.phase = 'passing';
    broadcast(room);
    setTimeout(() => { if (room.phase === 'passing') { nextTurn(room); broadcast(room); } }, 1200);
    return;
  }
  const moves = movable(room, ti);
  if (moves.length === 0) {
    room.phase = 'passing';            // transient: blocks a second roll, single pass timer
    broadcast(room);
    setTimeout(() => { if (room.phase === 'passing') { nextTurn(room); broadcast(room); } }, 900);
    return;
  }
  room.phase = 'await_move';
  if (autoSeat(p) || forced) {
    broadcast(room);
    setTimeout(() => { if (room.phase === 'await_move' && room.turn === ti) doMove(room, ti, aiPick(room, ti, movable(room, ti))); }, 600);
  } else if (moves.length === 1) {
    broadcast(room);                   // only one legal piece: move it for them
    setTimeout(() => doMove(room, ti, moves[0]), 450);
  } else {
    armTurnTimer(room);
    broadcast(room);
  }
}

function doMove(room, ti, tok) {
  if (room.phase !== 'await_move' || room.turn !== ti) return;
  if (!movable(room, ti).includes(tok)) return;
  touch(room);
  clearTurnTimer(room);
  applyMove(room, ti, tok);
  if (checkWin(room, ti)) { broadcast(room); return; }
  const p = room.players[ti];
  const ev = room.lastEvent && room.lastEvent.kind;
  // Ludo King rules: a six, a kill, or bringing a token home = roll again.
  const bonus = room.rolledSix || ev === 'capture' || ev === 'home';
  if (bonus) {
    if (!room.rolledSix) room.sixRun = 0;
    room.phase = 'await_roll';
    armTurnTimer(room);
    broadcast(room);
    if (autoSeat(p)) setTimeout(() => doRoll(room, ti), 900);
  } else { nextTurn(room); broadcast(room); }
}

function scheduleAuto(room) {
  const i = room.turn, p = room.players[i];
  if (!p || room.phase !== 'await_roll') return;
  if (autoSeat(p)) setTimeout(() => doRoll(room, i), 800);
}

// ---- colours ----------------------------------------------------------------
function freeColors(room) { return COLORS.filter((c) => !room.players.some((p) => p.color === c)); }
function setColor(room, p, color) {
  if (room.phase !== 'lobby') return false;
  if (!COLORS.includes(color)) return false;
  if (room.players.some((q) => q !== p && q.color === color)) return false;
  p.color = color; p.seat = COLORS.indexOf(color);
  return true;
}

// ---- lifecycle --------------------------------------------------------------
function clearAway(room, keep) {
  room.players.forEach((p) => { if (!keep.includes(p) && p.awayTimer) { clearTimeout(p.awayTimer); p.awayTimer = null; } });
}

function startGame(room, fillCpu) {
  if (room.phase !== 'lobby') return;
  clearQuick(room);
  let keep = room.players.filter((p) => p.ai || p.connected); // lobby has no grace
  clearAway(room, keep); room.players = keep;
  if (room.stake > 0) room.players = room.players.filter((p) => !p.ai);   // paid: humans only, pot must balance
  if (room.players.length < 1) return;
  if (room.stake > 0 && room.players.length < 2) {
    sendTo(room, room.hostUid, { t: 'error', msg: 'Need at least 2 players for a paid game' });
    broadcast(room); return;
  }
  // Quick match (free): empty seats become CPUs so the game starts now.
  if (fillCpu && room.stake === 0) {
    let n = 1;
    while (room.players.length < MAX_PLAYERS) room.players.push(newPlayer(null, null, 'CPU ' + (n++), true));
  }
  // give anyone without a colour the next free one, then order turns by colour
  room.players.forEach((p) => { if (!p.color) { const c = freeColors(room)[0]; p.color = c; p.seat = COLORS.indexOf(c); } });
  room.players.sort((a, b) => a.seat - b.seat);
  room.players.forEach((p) => { p.toks = [-1, -1, -1, -1]; p.done = false; p.paid = false; p.settled = false; });
  room.pot = 0;                                              // built from escrow acks
  room.gameNo++;
  room.turn = 0; room.phase = 'await_roll'; room.winner = -1; room.rolledSix = false; room.dice = 0; room.lastEvent = { kind: 'start' };
  touch(room);
  armTurnTimer(room);
  broadcast(room);
  scheduleAuto(room);
}

// After a game: keep connected players, promote spectators into free seats,
// back to the lobby (colours kept; newcomers pick theirs).
function rematch(room) {
  if (room.phase !== 'over') return;
  // The pot is owed to a human who hasn't collected yet (dropped before the
  // client could settle): hold the table for them — never discard a winner.
  const w = room.winner >= 0 ? room.players[room.winner] : null;
  if (room.stake > 0 && w && !w.settled) {
    sendTo(room, room.hostUid, { t: 'error', msg: 'Waiting for ' + w.name + ' to collect the pot' });
    return;
  }
  clearTurnTimer(room);
  const keep = room.players.filter((p) => p.conn && p.connected && !p.ai);
  clearAway(room, keep);
  const promoted = [];
  while (keep.length + promoted.length < MAX_PLAYERS && room.spectators.length) {
    const s = room.spectators.shift();
    promoted.push(newPlayer(s.conn, s.uid, s.name));
    if (s.conn) s.conn.role = 'player';
  }
  room.players = keep.concat(promoted);
  room.players.forEach((p) => { p.toks = [-1, -1, -1, -1]; p.done = false; p.paid = false; p.settled = false; });
  if (!room.players.some((p) => p.uid === room.hostUid) && room.players[0]) room.hostUid = room.players[0].uid;
  room.phase = 'lobby'; room.winner = -1; room.turn = 0; room.dice = 0; room.pot = 0; room.lastEvent = null;
  touch(room);
  if (room.public) armQuick(room);
  broadcast(room);
}

// ---- quick match auto-start ----------------------------------------------
// A public (quick-match) lobby starts by itself: at once when 4 are seated,
// otherwise after QUICK_MS — CPU-filled when free, or as soon as 2+ humans
// are in when there's a stake (CPUs can't pay into a pot).
const QUICK_MS = 20 * 1000;
function clearQuick(room) { if (room.quickTimer) { clearTimeout(room.quickTimer); room.quickTimer = null; } room.quickAt = 0; }
function armQuick(room) {
  clearQuick(room);
  if (room.phase !== 'lobby' || !room.public) return;
  room.quickAt = Date.now() + QUICK_MS;
  room.quickTimer = setTimeout(() => { room.quickTimer = null; quickTick(room); }, QUICK_MS);
}
function quickTick(room) {
  if (room.phase !== 'lobby' || !room.public) return;
  const humans = room.players.filter((p) => !p.ai && p.connected).length;
  if (humans === 0) return;
  if (room.stake === 0) startGame(room, true);
  else if (humans >= 2) startGame(room, false);
  else armQuick(room);                       // paid: keep waiting for a second human
}
function quickCheck(room) {
  if (room.phase !== 'lobby' || !room.public) return;
  if (room.players.filter((p) => !p.ai).length >= MAX_PLAYERS) startGame(room, false);
  else if (!room.quickTimer) armQuick(room);
}

// ---- views + broadcast ------------------------------------------------------
function view(room) {
  const cur = room.players[room.turn];
  const win = room.winner >= 0 ? room.players[room.winner] : null;
  return {
    t: 'state', code: room.code, phase: room.phase, turn: room.turn, dice: room.dice,
    turnUid: cur ? cur.uid : null, winnerUid: win ? win.uid : null,
    stake: room.stake, pot: room.pot, hostUid: room.hostUid,
    gameNo: room.gameNo, rollNo: room.rollNo, turnDeadline: room.turnDeadline, now: Date.now(),
    event: room.lastEvent, freeColors: freeColors(room), quickAt: room.quickAt, public: room.public,
    players: room.players.map((p) => ({
      uid: p.uid, seat: p.seat, name: p.name, color: p.color, ai: !!p.ai,
      toks: p.toks, done: !!p.done, connected: !!p.connected, paid: !!p.paid, settled: !!p.settled,
    })),
    spectators: room.spectators.map((s) => ({ uid: s.uid, name: s.name })),
  };
}
function everyone(room) {
  const out = [];
  room.players.forEach((p) => { if (p.conn) out.push(p); });
  room.spectators.forEach((s) => { if (s.conn) out.push(s); });
  return out;
}
function broadcast(room) {
  const msg = view(room);
  everyone(room).forEach((m) => m.conn.send(msg));
  room.lastEvent = null;   // events are one-shot
}
function sendTo(room, uid, obj) {
  const m = everyone(room).find((x) => x.uid === uid);
  if (m) m.conn.send(obj);
}
function chat(room, from, text, toUid) {
  const msg = { t: 'chat', from: from.name, fromUid: from.uid, text: String(text || '').slice(0, 140), to: toUid || null };
  if (toUid) { sendTo(room, toUid, msg); if (from.conn) from.conn.send(msg); }
  else everyone(room).forEach((m) => m.conn.send(msg));
}
function memberOf(room, conn) {
  return room.players.find((p) => p.conn === conn) || room.spectators.find((s) => s.conn === conn) || null;
}
function idxOfConn(room, conn) { return room.players.findIndex((p) => p.conn === conn); }

// ---- message handling -------------------------------------------------------
function handle(conn, m) {
  if (!m || typeof m.t !== 'string') return;
  const uid = String(m.uid || conn.uid || conn.cid).slice(0, 48);
  conn.uid = conn.uid || uid;

  if (m.t === 'create' || m.t === 'join' || m.t === 'quick' || m.t === 'resume') {
    if (conn.room) leave(conn, true);
  }

  if (m.t === 'create') {
    const room = newRoom(m.stake, m.public !== false);
    room.hostUid = conn.uid;
    const p = newPlayer(conn, conn.uid, m.name); room.players.push(p);
    if (m.color) setColor(room, p, m.color);
    rooms.set(room.code, room);
    conn.room = room.code; conn.role = 'player';
    conn.send({ t: 'joined', code: room.code, uid: conn.uid, role: 'player', host: true });
    broadcast(room);
    return;
  }

  if (m.t === 'join') {
    const room = rooms.get(String(m.code || '').toUpperCase().trim());
    if (!room) return conn.send({ t: 'error', msg: 'Room not found', code: 'nf' });
    return seat(room, conn, m.name, m.color);
  }

  if (m.t === 'resume') {
    const room = rooms.get(String(m.code || '').toUpperCase().trim());
    if (!room) return conn.send({ t: 'error', msg: 'That game has ended', code: 'nf' });
    const p = room.players.find((x) => x.uid === conn.uid);
    if (p) {
      if (p.awayTimer) { clearTimeout(p.awayTimer); p.awayTimer = null; }
      if (p.conn && p.conn !== conn) { try { p.conn.room = null; p.conn.close(); } catch (e) {} }
      p.conn = conn; p.connected = true;
      conn.room = room.code; conn.role = 'player';
      conn.send({ t: 'joined', code: room.code, uid: conn.uid, role: 'player', host: room.hostUid === conn.uid, resumed: true });
      touch(room);
      if (room.players[room.turn] === p) armTurnTimer(room);
      broadcast(room);
      return;
    }
    return seat(room, conn, m.name, m.color);
  }

  if (m.t === 'quick') {
    const stake = Math.max(0, m.stake | 0);
    let room = null;
    for (const r of rooms.values()) {
      if (r.public && r.phase === 'lobby' && r.stake === stake && r.players.length < MAX_PLAYERS) { room = r; break; }
    }
    if (!room) {
      room = newRoom(stake, true);
      room.hostUid = conn.uid;
      const p = newPlayer(conn, conn.uid, m.name); room.players.push(p);
      if (m.color) setColor(room, p, m.color);
      rooms.set(room.code, room);
      conn.room = room.code; conn.role = 'player';
      conn.send({ t: 'joined', code: room.code, uid: conn.uid, role: 'player', host: true });
      armQuick(room);
      broadcast(room);
      return;
    }
    seat(room, conn, m.name, m.color);
    quickCheck(room);
    return;
  }

  // in-room actions
  const room = rooms.get(conn.room);
  if (!room) return;
  touch(room);
  const me = memberOf(room, conn);
  if (!me) return;

  if (m.t === 'start') {
    if (me.uid === room.hostUid && room.phase === 'lobby') startGame(room, room.public);
  } else if (m.t === 'rematch') {
    if (me.uid === room.hostUid && room.phase === 'over') rematch(room);
  } else if (m.t === 'color') {
    const i = idxOfConn(room, conn);
    if (i >= 0) { if (setColor(room, room.players[i], String(m.color || ''))) broadcast(room); else conn.send({ t: 'error', msg: 'That colour is taken' }); }
  } else if (m.t === 'name') {
    me.name = cleanName(m.name); broadcast(room);
  } else if (m.t === 'stake') {
    // host sets the Happy Paisa stake while the room is in the lobby (private rooms only —
    // quick-match rooms are matched by stake)
    if (me.uid === room.hostUid && room.phase === 'lobby' && !room.public) {
      const v = Math.max(0, m.stake | 0);
      if ([0, 10, 20, 50, 100, 200, 500].includes(v)) { room.stake = v; broadcast(room); }
    }
  } else if (m.t === 'roll') {
    const i = idxOfConn(room, conn); if (i >= 0) doRoll(room, i);
  } else if (m.t === 'move') {
    const i = idxOfConn(room, conn); if (i >= 0) doMove(room, i, m.ti | 0);
  } else if (m.t === 'escrowed') {
    const i = idxOfConn(room, conn); const p = room.players[i];
    if (p && room.stake > 0 && room.phase !== 'lobby' && !p.paid) {
      p.paid = true;
      room.pot = room.stake * room.players.filter((x) => x.paid).length;
      broadcast(room);
    }
  } else if (m.t === 'settled') {
    const i = idxOfConn(room, conn);
    if (i >= 0 && room.phase === 'over' && room.winner === i) { room.players[i].settled = true; broadcast(room); }
  } else if (m.t === 'chat') {
    chat(room, me, m.text, m.to ? String(m.to).slice(0, 48) : null);
  } else if (m.t === 'leave') {
    leave(conn, false, !!m.forfeit);
  }
}

// Seat an incoming connection: a player if there's an open seat and the game
// hasn't started, otherwise a spectator. A uid already present is rebound.
function seat(room, conn, name, color) {
  const existing = room.players.find((p) => p.uid === conn.uid) || room.spectators.find((s) => s.uid === conn.uid);
  if (existing) {
    if (existing.awayTimer) { clearTimeout(existing.awayTimer); existing.awayTimer = null; }
    if (existing.conn && existing.conn !== conn) { try { existing.conn.room = null; existing.conn.close(); } catch (e) {} }
    existing.conn = conn; existing.connected = true;
    if (name) existing.name = cleanName(name);
    conn.room = room.code; conn.role = room.players.includes(existing) ? 'player' : 'spectator';
    conn.send({ t: 'joined', code: room.code, uid: conn.uid, role: conn.role, host: room.hostUid === conn.uid, resumed: true });
    return broadcast(room);
  }
  conn.room = room.code;
  if (room.phase === 'lobby' && room.players.length < MAX_PLAYERS) {
    const p = newPlayer(conn, conn.uid, name); room.players.push(p);
    if (color) setColor(room, p, color);
    conn.role = 'player';
    conn.send({ t: 'joined', code: room.code, uid: conn.uid, role: 'player', host: false });
  } else if (room.spectators.length < MAX_SPECT) {
    room.spectators.push({ uid: conn.uid, conn, name: cleanName(name || 'Guest') });
    conn.role = 'spectator';
    conn.send({ t: 'joined', code: room.code, uid: conn.uid, role: 'spectator', host: false });
  } else {
    conn.room = null;
    return conn.send({ t: 'error', msg: 'Room is full (8 people)' });
  }
  broadcast(room);
}

// `silent` = the socket is moving to another room.
function leave(conn, silent, forfeit) {
  const room = rooms.get(conn.room);
  conn.room = null;
  if (!room) return;
  const pi = room.players.findIndex((p) => p.conn === conn);
  const si = room.spectators.findIndex((s) => s.conn === conn);

  if (si >= 0) { room.spectators.splice(si, 1); }
  else if (pi >= 0) {
    const p = room.players[pi];
    if (room.phase === 'lobby') {
      room.players.splice(pi, 1);
    } else if (room.phase === 'over') {
      p.connected = false; p.conn = null;          // room lingers for resume/settle
    } else if (room.stake > 0 && (forfeit || !p.paid)) {
      // A seat that never paid into the pot (escrow failed) — or explicitly
      // forfeits — is out at once: it must not play on against paid seats.
      p.connected = false; p.conn = null; p.done = true;
    } else {
      // mid-game: go "away". Auto-played meanwhile; after the grace period a
      // paid seat forfeits (skipped), a free seat just keeps being auto-played.
      p.connected = false; p.conn = null;
      if (p.awayTimer) clearTimeout(p.awayTimer);
      const gameNo = room.gameNo;
      p.awayTimer = setTimeout(() => {
        p.awayTimer = null;
        if (p.connected || room.gameNo !== gameNo || !room.players.includes(p) || room.phase === 'over' || room.phase === 'lobby') return;
        if (room.stake > 0) { p.done = true; if (!lastStanding(room) && room.players[room.turn] === p) { nextTurn(room); } }
        broadcast(room);
        maybeDelete(room);
      }, GRACE_MS);
    }
  } else { return; }

  if (conn.uid === room.hostUid) {
    const nextHost = room.players.find((p) => p.conn) || room.spectators[0];
    if (nextHost) room.hostUid = nextHost.uid;
  }

  if (maybeDelete(room)) return;
  if (lastStanding(room)) { broadcast(room); return; }
  broadcast(room);
  // Keep the game moving if whoever is on the clock just went away.
  if ((room.phase === 'await_roll' || room.phase === 'await_move') && pi === room.turn) {
    clearTurnTimer(room);
    if (room.players[pi].done) { nextTurn(room); broadcast(room); }
    else if (room.phase === 'await_roll') scheduleAuto(room);
    else {
      const i = room.turn, mv = movable(room, i);
      if (mv.length) setTimeout(() => { if (room.phase === 'await_move' && room.turn === i) doMove(room, i, aiPick(room, i, mv)); }, 600);
      else { nextTurn(room); broadcast(room); }
    }
  }
}

// A room dies when nobody is connected AND nobody could still come back.
function maybeDelete(room) {
  const connected = everyone(room).length;
  const pending = room.players.some((p) => !p.connected && p.awayTimer);
  const w = room.winner >= 0 ? room.players[room.winner] : null;
  const unsettled = room.phase === 'over' && room.stake > 0 && w && !w.settled;
  if (connected === 0 && !pending && !unsettled) {
    clearTurnTimer(room); clearQuick(room);
    room.players.forEach((p) => p.awayTimer && clearTimeout(p.awayTimer));
    rooms.delete(room.code);
    return true;
  }
  return false;
}

// ---- housekeeping -----------------------------------------------------------
setInterval(() => {
  const all = new Set();
  for (const room of rooms.values()) everyone(room).forEach((m) => all.add(m.conn));
  all.forEach((c) => { if (!c.alive) { c.close(); return; } c.alive = false; c.ping(); });
  const now = Date.now();
  for (const [code, room] of rooms) {
    if (now - room.lastActive > ROOM_TTL) {
      clearTurnTimer(room); clearQuick(room);
      room.players.forEach((p) => p.awayTimer && clearTimeout(p.awayTimer));
      rooms.delete(code);
    }
  }
}, 10000);   // 10s: behind Render's proxy an abrupt drop is only seen via a missed pong

// ---- attach to the main HTTP server ----------------------------------------
function attach(server) {
  server.on('upgrade', (req, socket) => {
    let pathname = '/';
    try { pathname = new URL(req.url, 'http://x').pathname; } catch (e) {}
    if (pathname !== '/ludo') return;   // another module (voice relay) may own this path
    const key = req.headers['sec-websocket-key'];
    if (!key) { try { socket.destroy(); } catch (e) {} return; }
    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      'Sec-WebSocket-Accept: ' + accept(key) + '\r\n\r\n'
    );
    const conn = new Conn(socket, {
      onmessage: (txt) => {
        let m; try { m = JSON.parse(txt); } catch (e) { return; }
        try { handle(conn, m); } catch (e) { /* never let one message kill the socket */ }
      },
      onclose: () => { try { leave(conn, false); } catch (e) {} },
    });
    conn.send({ t: 'welcome', cid: conn.cid });
  });
  console.log('  [ludo] websocket rooms live at /ludo');
  return { rooms };
}

module.exports = { attach, Conn, accept, _rooms: rooms, _GRACE_MS: GRACE_MS, _TURN_MS: TURN_MS };
