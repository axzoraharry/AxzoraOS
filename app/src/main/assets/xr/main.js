/* Satyug XR Room v1 — Babylon 9.30, vendored.
 *
 * One room, one avatar (Aria), Mr Happy inside, one object (the Core), one
 * command line that mutates a simple STATE. The command pipeline is
 * window.satyugCommand(text) — the single seam that voice, typing, and
 * Mr Happy (the AI Game Master) all call.
 *
 * v1 changes, and why:
 *   - Babylon is bundled at ./babylon.js instead of loaded from
 *     cdn.babylonjs.com, so the room renders with the network off. v0 was a
 *     blank page offline, which made "local-first" a claim rather than a fact.
 *   - The room now says which world it is in. It asks the Satyug world server
 *     for /api/world/digest and shows the number it got back, or says OFFLINE.
 *     It never invents a digest, and it does not claim to be in a world it
 *     cannot see.
 */
(function () {
  const BUILD = { assets: '18.122', code: 202, dex: '18.121' };
  const canvas = document.getElementById('c');
  const fatal = document.getElementById('fatal');

  /* Fail visibly, not blankly. If the bundle is missing or refused, the page
   * says so instead of showing an empty canvas the user would read as broken. */
  if (typeof BABYLON === 'undefined') {
    fatal.style.display = 'grid';
    fatal.textContent = 'The 3D engine did not load. babylon.js is missing from this build, '
      + 'so no room can be drawn. This is a build fault, not a device fault.';
    return;
  }

  const engine = new BABYLON.Engine(canvas, true, { preserveDrawingBuffer: true, stencil: true });

  // ---- simple world STATE (the "one state" of v0) ----
  const STATE = { color: '#f0a73c', night: true, scale: 1, spinSec: 9, shape: 'sphere' };
  const COLORS = { red:'#ef4444', blue:'#3b82f6', green:'#22c55e', gold:'#f0a73c',
    yellow:'#eab308', purple:'#a855f7', white:'#ffffff', orange:'#fb923c' };
  const hex = (h) => BABYLON.Color3.FromHexString(h);

  const scene = new BABYLON.Scene(engine);
  function applyBg(){ scene.clearColor = hex(STATE.night ? '#05070c' : '#cfe0f5').toColor4(1); }
  applyBg();

  // camera at eye height; drag to look, WASD on desktop
  const cam = new BABYLON.UniversalCamera('cam', new BABYLON.Vector3(0, 1.6, 3.4), scene);
  cam.setTarget(new BABYLON.Vector3(0, 1.5, -2));
  cam.attachControl(canvas, true);
  cam.speed = 0.35; cam.minZ = 0.05;

  // lights
  new BABYLON.HemisphericLight('h', new BABYLON.Vector3(0, 1, 0), scene).intensity = 0.75;
  const key = new BABYLON.PointLight('k', new BABYLON.Vector3(2, 4, 2), scene); key.intensity = 0.7;

  // ---- the room ----
  const mat = (name, h) => { const m = new BABYLON.StandardMaterial(name, scene); m.diffuseColor = hex(h); return m; };
  const floor = BABYLON.MeshBuilder.CreateGround('floor', { width: 10, height: 10 }, scene);
  floor.material = mat('mfloor', '#152033');
  function wall(w, h, d, x, y, z) {
    const b = BABYLON.MeshBuilder.CreateBox('wall', { width: w, height: h, depth: d }, scene);
    b.position.set(x, y, z); b.material = mat('mwall', '#1b2740'); return b;
  }
  wall(10, 3, 0.1, 0, 1.5, -5); wall(10, 3, 0.1, 0, 1.5, 5);
  wall(0.1, 3, 10, -5, 1.5, 0); wall(0.1, 3, 10, 5, 1.5, 0);

  // ---- billboarded name tag ----
  function label(text, color, y, parent) {
    const dt = new BABYLON.DynamicTexture('dt', { width: 256, height: 64 }, scene, false);
    dt.hasAlpha = true;
    dt.drawText(text, 16, 46, 'bold 34px Segoe UI', color, 'transparent', true);
    const pl = BABYLON.MeshBuilder.CreatePlane('lbl', { width: 1.2, height: 0.3 }, scene);
    const lm = new BABYLON.StandardMaterial('lm', scene);
    lm.diffuseTexture = dt; lm.emissiveColor = new BABYLON.Color3(1, 1, 1);
    lm.opacityTexture = dt; lm.backFaceCulling = false;
    pl.material = lm; pl.position.y = y; pl.billboardMode = BABYLON.Mesh.BILLBOARDMODE_ALL;
    pl.parent = parent; return pl;
  }

  // ---- a character (capsule body + head + label) ----
  function character(name, bodyHex, x, z, labelColor) {
    const root = new BABYLON.TransformNode(name, scene); root.position.set(x, 0, z);
    const body = BABYLON.MeshBuilder.CreateCapsule('b', { height: 1.1, radius: 0.28 }, scene);
    body.position.y = 0.85; body.material = mat('mb', bodyHex); body.parent = root;
    const head = BABYLON.MeshBuilder.CreateSphere('hd', { diameter: 0.5 }, scene);
    head.position.y = 1.55; head.material = mat('mh', bodyHex); head.parent = root;
    label(name, labelColor || '#ffffff', 2.0, root);
    return root;
  }
  const aria = character('Aria', '#3b82f6', 1.7, -2.4, '#8fb4ff');
  // Mr Happy — the brain, a glowing orb that gently bobs
  const happy = character('Mr Happy', '#f0a73c', -1.7, -2.4, '#f0a73c');

  // ---- the ONE object: the Core ----
  let core = BABYLON.MeshBuilder.CreateSphere('core', { diameter: 1 }, scene);
  const coreMat = new BABYLON.StandardMaterial('coreMat', scene);
  coreMat.diffuseColor = hex(STATE.color); coreMat.emissiveColor = hex(STATE.color).scale(0.4);
  core.material = coreMat; core.position.set(0, 1.6, -2);

  function rebuildCore() {
    const pos = core.position.clone(); core.dispose();
    core = STATE.shape === 'cube'
      ? BABYLON.MeshBuilder.CreateBox('core', { size: 1 }, scene)
      : BABYLON.MeshBuilder.CreateSphere('core', { diameter: 1 }, scene);
    core.material = coreMat; core.position = pos; core.scaling.setAll(STATE.scale);
  }
  function applyState() {
    coreMat.diffuseColor = hex(STATE.color); coreMat.emissiveColor = hex(STATE.color).scale(0.4);
    core.scaling.setAll(STATE.scale); applyBg();
    document.getElementById('state').textContent =
      `state · ${STATE.shape} · ${STATE.color} · ${STATE.night ? 'night' : 'day'} · x${STATE.scale} · spin ${STATE.spinSec}s`;
  }
  applyState();

  // ---- which world is this room in? -------------------------------------
  /* The world lives on this device as a Node process (satyug-os on :8788).
   * The room asks it for the digest — the one number that says whether two
   * clients hold the same lattice — and prints what came back. If nothing
   * answers it says OFFLINE. It does not guess, and it never shows a digest
   * it did not receive.
   *
   * The endpoint is local on purpose: a file:// page reaching a remote origin
   * would be a network dependency this room does not need. */
  const worldEl = document.getElementById('world');
  const WORLD_ENDPOINTS = ['http://127.0.0.1:8788'];
  const WORLD_TIMEOUT_MS = 2500;
  const world = { state: 'idle', endpoint: null, digest: null, age: 0, error: null };

  /* /api/world/digest reports `rule` as an object (id, version, salt, ...).
   * String-concatenating it yields "[object Object]", so read the id. */
  const ruleName = (d) => (d && d.rule && (d.rule.id || d.rule.version))
    ? (d.rule.id || ('v' + d.rule.version)) : 'unknown';

  function drawWorld() {
    if (world.state === 'live' && world.digest) {
      const d = world.digest;
      worldEl.className = '';
      worldEl.innerHTML = 'world · <b>OBSERVED</b> · ' + d.assignHex
        + ' · ' + d.entries + ' entries · ' + d.supply + ' HP';
      worldEl.title = 'rule ' + ruleName(d) + ' · ' + world.endpoint + ' · read ' + world.age + 's ago';
    } else if (world.state === 'offline') {
      worldEl.className = 'off';
      worldEl.textContent = 'world · OFFLINE · no field answered on :8788';
      worldEl.title = 'The room is drawn regardless; only the world link is missing.';
    } else {
      worldEl.className = '';
      worldEl.textContent = 'world · connecting…';
    }
  }

  async function probeWorld() {
    const url = WORLD_ENDPOINTS[0];
    const ctl = typeof AbortController === 'function' ? new AbortController() : null;
    const timer = ctl ? setTimeout(() => ctl.abort(), WORLD_TIMEOUT_MS) : null;
    try {
      const res = await fetch(url + '/api/world/digest', {
        headers: { accept: 'application/json' },
        signal: ctl ? ctl.signal : undefined,
        cache: 'no-store',
      });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const j = await res.json();
      if (!j || !j.assignHex) throw new Error('no digest in reply');
      world.state = 'live';
      world.endpoint = url;
      world.digest = j;
      world.age = 0;
      world.error = null;
    } catch (e) {
      world.state = 'offline';
      world.digest = null;
      world.error = e && e.message ? e.message : String(e);
    } finally {
      if (timer) clearTimeout(timer);
      drawWorld();
    }
    return world.state;
  }

  /* One accessor for the native bridge and for voice, mirroring the browser
   * side's satyugLive.world: read what the room actually has, never a guess. */
  window.satyugWorld = {
    state: () => world.state,
    digest: () => world.digest,
    endpoint: () => world.endpoint,
    error: () => world.error,
    refresh: probeWorld,
    build: () => Object.assign({}, BUILD),
  };
  window.satyugWorld.compare = async () => {
    await probeWorld();
    return { state: world.state, digest: world.digest, endpoint: world.endpoint, error: world.error };
  };

  drawWorld();
  probeWorld();
  setInterval(probeWorld, 20000);

  // spin + bob animations in the render loop
  let t = 0;
  scene.onBeforeRenderObservable.add(() => {
    const dt = engine.getDeltaTime() / 1000; t += dt;
    core.rotation.y += (2 * Math.PI) * dt / STATE.spinSec;
    happy.position.y = 0.06 * Math.sin(t * 2);   // Mr Happy bobs
    aria.position.y  = 0.05 * Math.sin(t * 1.7 + 1);
  });

  // ---- WebXR (AR/VR) — enter-immersive button when supported ----
  scene.createDefaultXRExperienceAsync({ floorMeshes: [floor] }).catch(() => {});

  // ---- the ONE command pipeline (voice + typing + Mr Happy all call this) ----
  const sayEl = document.getElementById('say');
  const say = (m) => { sayEl.textContent = m; clearTimeout(say._t); say._t = setTimeout(() => sayEl.textContent = '', 3500); };

  window.satyugCommand = function (raw) {
    const s = (raw || '').toLowerCase().trim(); if (!s) return 'empty';
    /* "world" is answered from what the room actually holds, then refreshed for
     * next time. It reports OFFLINE rather than a remembered number. */
    if (s === 'world' || s.includes('which world') || s.includes('the world')) {
      if (world.state === 'live' && world.digest) {
        const d = world.digest;
        say('World ' + d.assignHex + ' · ' + d.entries + ' entries · ' + d.supply + ' HP · rule ' + ruleName(d));
        probeWorld();
        return 'world:' + d.assignHex;
      }
      say('No world answered on :8788 — the room is offline. The room itself still works.');
      probeWorld();
      return 'world:offline';
    }
    let did = [];
    for (const k in COLORS) if (s.includes(k)) { STATE.color = COLORS[k]; did.push('color ' + k); }
    if (s.includes('day'))   { STATE.night = false; did.push('day'); }
    if (s.includes('night')) { STATE.night = true;  did.push('night'); }
    if (s.includes('grow') || s.includes('big'))   { STATE.scale = 1.8; did.push('grow'); }
    if (s.includes('shrink') || s.includes('small')){ STATE.scale = 0.6; did.push('shrink'); }
    if (s.includes('fast')) { STATE.spinSec = 2.5; did.push('spin fast'); }
    if (s.includes('slow')) { STATE.spinSec = 16;  did.push('spin slow'); }
    let shapeChanged = false;
    if (s.includes('cube') || s.includes('box'))  { STATE.shape = 'cube';   shapeChanged = true; did.push('cube'); }
    if (s.includes('sphere') || s.includes('ball')){ STATE.shape = 'sphere'; shapeChanged = true; did.push('sphere'); }
    if (shapeChanged) rebuildCore();
    if (s.includes('greet') || s.includes('hello') || s.includes('hi')) { say('Aria: Welcome to your world, sir.'); did.push('greet'); }
    applyState();
    say(did.length ? '✓ ' + did.join(', ') : 'heard: "' + s + '" (no match)');
    return did.join(',') || 'nomatch';
  };

  // ---- wire the UI ----
  const cmd = document.getElementById('cmd');
  const run = () => { const v = cmd.value; cmd.value = ''; window.satyugCommand(v); };
  document.getElementById('go').onclick = run;
  cmd.addEventListener('keydown', e => { if (e.key === 'Enter') run(); });

  const mic = document.getElementById('mic');
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (SR) {
    const rec = new SR(); rec.lang = 'en-US'; rec.interimResults = false;
    mic.onclick = () => { try { rec.start(); say('listening…'); } catch (e) {} };
    rec.onresult = e => { const txt = e.results[0][0].transcript; say('“' + txt + '”'); window.satyugCommand(txt); };
    rec.onerror = () => say('mic error — type instead');
  } else { mic.onclick = () => say('voice not available here — type'); }

  engine.runRenderLoop(() => scene.render());
  window.addEventListener('resize', () => engine.resize());
  window.addEventListener('load', () => say('Room ready. Say or type a command.'));
})();
