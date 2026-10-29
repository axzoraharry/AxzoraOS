/*
 * Axzora voice relay — the fallback path for calls between phones that can't
 * reach each other directly (two phones on mobile data behind carrier NAT,
 * no TURN relay configured). Each phone opens a WebSocket at
 *   /voice?call=<callId>&uid=<uid>
 * and streams small binary audio frames; every frame is forwarded, untouched,
 * to the other phone(s) in the same call. The server never decodes audio.
 * Pure Node, zero dependencies (shares the WebSocket codec with ludo.js).
 */
'use strict';
const { Conn, accept } = require('./ludo');

const rooms = new Map();            // callId -> Set<Conn>
const MAX_PER_CALL = 4;

function attach(server) {
  server.on('upgrade', (req, socket) => {
    let u; try { u = new URL(req.url, 'http://x'); } catch (e) { return; }
    if (u.pathname !== '/voice') return;
    const key = req.headers['sec-websocket-key'];
    const callId = String(u.searchParams.get('call') || '').slice(0, 64);
    const uid = String(u.searchParams.get('uid') || '').slice(0, 48);
    if (!key || !callId) { try { socket.destroy(); } catch (e) {} return; }
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + accept(key) + '\r\n\r\n');
    try { socket.setNoDelay(true); } catch (e) {}

    let room = rooms.get(callId);
    if (!room) { room = new Set(); rooms.set(callId, room); }
    if (room.size >= MAX_PER_CALL) { try { socket.destroy(); } catch (e) {} return; }

    const conn = new Conn(socket, {
      onbinary: (buf) => { room.forEach((c) => { if (c !== conn) c.sendBinary(buf); }); },
      onmessage: (txt) => {
        // tiny control channel: {t:'ping'} -> {t:'pong'}; anything else is fanned out
        let m; try { m = JSON.parse(txt); } catch (e) { return; }
        if (m.t === 'ping') { conn.send({ t: 'pong' }); return; }
        room.forEach((c) => { if (c !== conn) c.send(Object.assign({ from: uid }, m)); });
      },
      onclose: () => {
        room.delete(conn);
        room.forEach((c) => c.send({ t: 'peer-left', uid }));
        if (room.size === 0) rooms.delete(callId);
      },
    });
    conn.uid = uid;
    room.add(conn);
    conn.send({ t: 'welcome', peers: room.size - 1 });
    room.forEach((c) => { if (c !== conn) c.send({ t: 'peer-joined', uid }); });
  });
  setInterval(() => {
    for (const room of rooms.values()) room.forEach((c) => { if (!c.alive) { c.close(); return; } c.alive = false; c.ping(); });
  }, 10000);
  console.log('  [voice] audio relay live at /voice');
  return { rooms };
}
module.exports = { attach, _rooms: rooms };
