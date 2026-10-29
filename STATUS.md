# AxzoraOS — Mr Happy — Status & Next Steps

> Honest, verified snapshot of what's built and working on this phone, and what's
> genuinely next. Legend: ✅ **works now** · 🔧 **buildable (no credentials)** ·
> 🔬 **research / needs real infra (API key, GPU, testbed, EMS, licensed finance)**.

**Current shipped build:** ✅ **18.128 / code 208** (OTA channel live at `server/update/update.json`).

---

## 1 · What works on the phone right now (all ✅, verified live)

| App / Capability | What it does | Status |
|---|---|---|
| **Mr Happy chat** | Real AI (DeepSeek via `:8002` bridge), tool‑calling | ✅ |
| Mr Happy **Speaks** | 🔊 auto‑speak + 🔊 per‑reply + 🎤 hold‑to‑talk mic | ✅ |
| Mr Happy **codegen** | "make me a login page" → HTML/Tailwind/React | ✅ |
| Mr Happy **opens apps** | "open terminal / browser / satyug / ceoAdmin" | ✅ |
| **Omni Terminal** | Neural command interface (live world/HP/bridge, honest sims) | ✅ |
| **CEO Admin** | Happy Paisa: claim → load users → **mint / send / notify** (server ledger) | ✅ |
| **Web Browser** | browse + DeepSeek assistant | ✅ |
| **World Graph** | Mr Happy knows linked devices/vehicle/wallet/family; `world_status` | ✅ |
| **Shop Agent** | real catalog (FakeStore + DummyJSON, ~120 products, cached to disk) + grounded AI recommendations that never invent products | ✅ |
| **Family Map** | real live flights (keyless OpenSky, centered on the phone's GPS) + God's Evil Eye (nazar) protect overlay over family | ✅ |
| **God's Eye View** | the real open‑source OSINT globe: live aircraft/ships/satellites/earthquakes/cameras on a Cesium 3D Earth (keyless OSM fallback), served from `:4173`, opened from a home tile | ✅ |
| **Satyug Universe** | the Axzora Super App (bundled offline libs+models) | ✅ |
| **Self‑healing keeper** | supervises 3000/3001/8002/8787/8090/4173, restarts on idle‑drop | ✅ |

**Live ports (all 200):** `3000` OpenRoom · `3001` /api · `8002` Mr Happy bridge ·
`8090` Shop Agent · `8787` OTA + DeepSeek proxy · `8100` fleet coordinator ·
`8170` axzora‑apps · `8092` game server.

> **Mr Happy brain = deepseek‑key + automatic failover (verified 2026‑09‑05).** The
> bridge (`:8002`) now loads `DEEPSEEK_API_KEY` from the same `deepseek-key.json` the
> `:8787` proxy uses, so its brain router has **two independent DeepSeek paths**: the
> local `:8787` proxy (primary) and direct `https://api.deepseek.com` (fallback).
> Verified live: with `:8787` fully killed, the bridge still answered via direct
> DeepSeek. This removes the intermittent `(offline) No model reached` that used to
> appear on long/codegen prompts.

---

## 2 · Half‑built / needs a decision

| Layer | State | Honest note |
|---|---|---|
| **Fleet of PCs** (GLM‑5.3, turbovec, real screenshot‑to‑code) | 🔧 coordinator + worker **complete** (`VPS-SETUP.md`, `PC-WORKER.md`) | **0 workers connected.** The phone can't run the heavy jobs (no GPU). Run `agent/run-agent.sh` on a Linux PC to unlock them. |
| **Speech server** (`:8110`) | 🔧 `speech-server.py` written + validated (edge‑tts TTS + `/chat` voice loop) | **not running** — needs `uvicorn` in your proot. Bridge `/tts` proxy + Speak button already use it when up. |
| **Voice/vision in the phone** | ✅ Speaker output (TTS) works; 🎤 mic input depends on Web Speech + secure context | Degrades gracefully; native Mr Happy (Vosk wake word) is the reliable mic path. |

---

## 3 · Genuinely 🔬 research / needs real credentials (NOT faked, NOT runnable here)

| Capability | What it needs | This phone |
|---|---|---|
| **Real Mercedes telemetry** | Mercedes‑Benz Developer API key + enrolled C‑Class | ❌ no key |
| **Live EMS / ambulance dispatch** | a real EMS source / partnership | ❌ no public feed |
| **On‑chain Happy Paisa** | real deployed contract + wallet + signer | ❌ server ledger only |
| **Real currency conversion / payments** | licensed financial partner + compliance | ❌ not licensed |
| **Locally‑run LLM (GLM‑5.3)** | 16 GB + GPU + ~GBs weights + llama.cpp | ❌ 15 GB, no GPU, aarch64/py3.14 |
| **Local ASR (faster‑whisper)** | ctranslate2 wheel (aarch64/py3.14) | ❌ wheel unavailable |
| **AI‑RAN / O‑RAN / 6G / digital twin** | lab testbed (ns‑3, OpenAirInterface, CARLA) | ❌ testbed only |

---

## 4 · The honest engineering truth

- **This is a real, functional personal AI OS on the phone.** Mr Happy talks, thinks
  (DeepSeek), generates code, opens apps, manages Happy Paisa, knows your world
  graph, and runs a live command terminal — all from real, verifiable endpoints.
- **Nothing is faked.** The terminal and apps read live state from the bridge
  (`/world`, `/v1/hp/market`, `/echo`, `/v1/speech/host`) and label simulations as
  **SIMULATED**. No fabricated "8.4M agents / 2.4M online / 98.7% memory".
- **The hardware is the wall, not the code.** No GPU (15 GB, aarch64, python 3.14,
  broken git, no CUDA) means the **big local models and native ASR can't run here**.
  Those belong on your PCs via the fleet — which is ready to use.

---

## 5 · Recommended next step (pick one)

1. **Connect a PC as a fleet worker** (unlocks GLM‑5.3, turbovec, real screenshot‑to‑code) —
   follow `axzora-fleet/PC-WORKER.md`.
2. **Start the speech server** so Mr Happy uses the high‑quality edge‑tts voice
   (`uvicorn speech-server:app --port 8110` in proot).
3. **Polish the Mr Happy chat UX** (the main surface you use).
4. **Stop here** — you have a complete, honest, working AxzoraOS.

---

## 6 · The 7 questions, answered honestly (recap)
> How does it run? Where? What permission? How verified? How is failure handled?
> How is privacy preserved? How is the user in control?

Every feature in §1 answers these: local‑first where possible, zero‑trust admin code
+ device claim for money, keeper self‑heals failures, data lives on the device, and
the human approves every consequential action. Deep details are in
`MrHappy-6G/docs/01..12` (the Phase 0 System Constitution).

---

## 7 · Recent work (changelog)

| When | What changed | Verified |
|---|---|---|
| 2026‑09‑05 | **Shop Agent** (`:8090`): real catalog from FakeStore + DummyJSON (~120 products, 15 categories), cached to `catalog-cache.json` for instant restarts. `recommend(query)` sends the **full** catalog to the brain (was only first 60), and no longer falsely reports "(Brain offline)" when the brain is up but finds no match. | `/recommend` + `/chat` return honest, ground‑truth picks. |
| 2026‑09‑05 | **Mr Happy brain failover**: bridge loads the DeepSeek key from `deepseek-key.json` and answers directly via `api.deepseek.com` if the `:8787` proxy drops. | Killed `:8787`, bridge still answered (`direct-failover-ok`), then restored. |
| 2026‑09‑05 | Root‑caused the intermittent "(offline) No model reached": it was the `:8787` proxy **not being supervised** (the keeper had died; the grid‑watchdog's service list omits `8787/3000/3001/8090`). Restarted the full stack. | All ports UP (3000/3001/8002/8090/8787). |
| 2026‑09‑05 | **Family Map** (appId 121): new `FamilyMap` desktop app. Live flights (keyless OpenSky) drawn as markers on a self‑contained SVG map, centered on the phone's real GPS; God's Evil Eye (nazar) protect amulet rendered over family. `/api/family` proxy (`:3000`→`:3001`) with client‑side retry‑backoff to ride OpenSky's throttling. | `/api/family` returns real flights (e.g. 53 over Delhi, 23 over Mumbai); chunk served by `:3000`; 0 build errors. |
| 2026‑09‑05 | **God's Evil Eye on the home Family Map** (native, v18.122): `LocationScreen` (the AxzoraOS home "Family map") now shows a pulsing evil‑eye (nazar) protect amulet over the real shared family map. APK rebuilt, OTA pushed. | `compileReleaseKotlin` clean; APK v18.122/202 on OTA; `/update/download` serves 109 MB APK. |
| 2026‑09‑05 | **God's Eye View** (v18.123): found the real open‑source project already on the device (`gods-eye-view-main`), migrated it into the app‑data dir so node can `dlopen` the native rollup binary (it couldn't from `/storage/emulated`), built the production bundle, served it on `:4173`, added a **"God's Eye" home tile**, and supervised it via the keeper. Uses keyless OpenStreetMap fallback (real, no key). | `vite build` clean (9.6s); static server `http=200` on Cesium + bundle; OTA serves 18.123 APK. |
| 2026‑09‑05 | **God's Eye View in-app** (v18.124): new `GodsEyeActivity` hosts the globe in a full‑screen geolocation‑enabled WebView (flies to your live location, pinch‑zoom, hardware‑accelerated). Home tile now opens in‑app (not the browser). Mr Happy toolbar added `godseye` ("open god's eye"). Bundled my‑location fly‑to. Verified open tracking sources reachable. | `vite build` clean; God's Eye View bundle has geo fly‑to; APK v18.124/204 on OTA; `http=200` on adsb/ais/celestrak/usgs/firms/osm. |
| 2026‑09‑05 | **Fixed "live data not tracking"** + **"Mr Happy not understanding"**: God's Eye View was served by a bare static server that returned 404/HTML for its ~25 `/api/*` proxies (OpenSky/AIS/CelesTrak/CCTV/radio). Now served by the Vite **dev** server which runs every proxy → real live data (7,600+ aircraft, 16,000+ satellites). Added a bridge `/godseye` endpoint + Mr Happy `godseye_status` tool so he reads the real tracked sky. Keeper updated to relaunch the dev server. | `/api/opensky` returns 7,658 live states; `/godseye` returns real aircraft+satellites; Mr Happy chunk has `godseye_status`. |
| 2026‑09‑05 | **DeepSeek API key wired in**: replaced the key in `deepseek-key.json` (canonical), so the `:8787` proxy + `:8002` bridge use it directly. Added a `DEEPSEEK_API_KEY`-backed `/api/ai` chat proxy to God's Eye View (its in‑globe "ask AI" now uses DeepSeek chat‑completions). Verified all three consumers answer with DeepSeek. | `:8787`, `:8002`, and God's Eye View `/api/ai` all return real DeepSeek responses. |
| 2026‑09‑06 | **FlowPack tile** (appId 123): a launcher tile for `aipack.flowagentautomation.com`. Now always opens in the phone's **external browser** (native `Axzora.openUrl` bridge, `window.open` fallback) rather than an in-app window. Wired into Mr Happy `open_app`. | OpenRoom frontend rebuilt clean (2m2s); `:3000` serves the FlowPack URL chunk + the `openUrl` browser-launch in the Shell bundle. |
| 2026‑09‑06 | **Goa Route Digital Twin** (honest build): a God's Eye command-center overlay + Mr Happy tool (`goa_twin`) grounded in **real** keyless Goa data — OSRM route (55.4 km / 61 min, OBSERVED), Open‑Meteo weather (LIVE), Overpass road network (LIVE when reachable). Traffic state is a **model estimate** (PREDICTED/INFERRED) and live Goa CCTV / GPS probe are honestly labeled **NO SOURCE** (no credential). Added `:4173/api/goa-twin` + bridge `:8002/goa`. | God's Eye built (8.9s) + OpenRoom built (2m); `/api/goa-twin` and `/goa` return real route/weather with honest data-source health. |
| 2026‑09‑06 | **Axzora World Monitor**: unified God's Eye overlay (`w` hotkey) + Mr Happy tool (`world_monitor`) that surfaces the on-device world — world graph (owner, Mate XT, Mercedes, Happy Paisa, Family Circle, Mr Happy) and shared world model (omega). The native Happy World Engine (procedural sim) is noted honestly as on‑device / not reachable over HTTP. Added `:4173/api/world-monitor` + bridge `:8002/worldmonitor`. | God's Eye built (9.1s) + OpenRoom built (2m13s); `/api/world-monitor` + `/worldmonitor` return 6 entities / omega 5 objects, LIVE-labeled. |
| 2026‑09‑06 | **Happy Paisa Wallet in OpenRoom** (appId 124) + Mr Happy `hp_wallet` tool: real HP from the on-device ledger — live market (`:8002/v1/hp/market`, $11.55/₹1000, cap 218k), 36 registered users (`:8787/wallet/directory`), real per-user balance (`/ledger/balance`, e.g. 44 HP), staking and history (`/ledger/staking|history`), and real transfers (`/ledger/transfer`). Honest: **server ledger, not on‑chain.** | OpenRoom built (2m45s); HappyPaisa chunk + `hp_wallet` served; verified live market/balance/history. |
| 2026‑09‑06 | **Happy Paisa deeper play**: added **stake/unstake** (`/ledger/stake|unstake`, real APR/lock) and **admin mint + overview** (`/admin/claim` → `/admin/overview` + `/ledger/mint`) to the OpenRoom wallet. Verified the real admin gate (claim device → overview: 36 users, circulating 100,000,705 HP, 45 ledger entries). | OpenRoom rebuilt (1m50s); chunk has staking+admin; claim/overview/balance verified live. |
| 2026‑09‑06 | **Fixed "Mr Happy doesn't understand Happy Paisa"**: the bridge world graph hardcoded the wallet as `balance: 0` (fabricated). `worldGraph()` now reads the **real ledger balance/staking** from `:8787` (live, labeled `source`), so Mr Happy reports real HP, never a false 0. Added HP to Mr Happy's system prompt, a `hp_wallet` tool, and `open_app happyPaisa`. | World now shows `balance 44 (live ledger)`; Mr Happy answers "balance is 44, live ledger". |
| 2026‑09‑06 | **GeoLibre GIS tile** (appId 125): added the hosted cloud-native GIS viewer (MapLibre + DuckDB-WASM + deck.gl) as an in-OpenRoom window — `viewer.geolibre.app` with a sample `.geolibre.json` project, plus "Blank map" and "Open in browser". Wired into Mr Happy `open_app geoLibre`. Honest: this embeds the **hosted** GeoLibre demo; a local/desktop build needs a Rust toolchain + Linux desktop deps (not possible on this ARM64 phone). | OpenRoom built (3m32s); GeoLibre chunk served (200); viewer + sample reachable (200). |
| 2026‑09‑06 | **EV Charging perception→payment loop**: bridge `/charge` orchestrates God's Eye perception (real nearest charging station via Overpass, OBSERVED) → user/ledger verification → trust+risk score → explicit-confirmation gate → **real Happy Paisa ledger transfer** → 4D event. Fails closed (requires "confirm payment", denies on insufficient balance / unknown user / no verified charger; never fabricates physical charging or a successful payment). Added Mr Happy `charge_session` tool + system prompt + OpenRoom **EV Charging** app (appId 126). | Verified: propose→Ather Grid OBSERVED, trust 0.9/LOW; authorize-without-confirm→CONFIRMATION_REQUIRED; authorize-with-confirm→real 43 HP transfer (balance 43→0), 4D event settled. |
| 2026‑09‑06 | **God's Eye perceive→act loop**: bridge `/v1/perceive` takes the **real accessibility screen text** + open apps (the live Android surface), grounds it into the 4D world, reasons with DeepSeek, and proposes one safe action. Honest: screen is read via **accessibility text, NOT a real camera/pixel vision model** (no MediaProjection/local vision model on this ARM64/no‑GPU phone). Detects payment intents and links to `/charge` (explicit confirmation required). Local‑only boundary. | `/v1/perceive` returns OBSERVED screen ground + `text-grounded/pixel:false` vision + DeepSeek action + payment-intent link. |
| 2026‑09‑06 | **Real screen capture (MediaProjection)** v18.125: native `ScreenCapture.kt` + `Axzora.requestScreenCapture()` bridge + `:8002/v1/screen-frame` endpoint serving the real captured PNG. Honest: yields real pixels; **pixel understanding still needs a vision model** this device lacks. | APK v18.125/205 on OTA; `/v1/screen-frame-status` returns `captured:false` until the one-time system permission is granted. |
| 2026‑09‑06 | **Money-rail hardening (auth token)**: shared local `axzora-token.json` gating money‑moving endpoints — ledger `/ledger/transfer`,`/stake`,`/unstake` now require `x-axzora-token` → `401 AUTH_REQUIRED`; bridge `/charge` **authorize** requires it (read-only `propose` open). Token exposed loopback‑only via `:8002/api/token`; frontend reads it. Keeper hardened with **per‑service backoff throttle** (no crash-loop). | no‑token transfer/authorize → `401 AUTH_REQUIRED`; token → works; all core ports UP; keeper stable (~9 min no relaunch lines). |
| 2026‑09‑06 | **AXZORA LIVI Deck** (head-unit UI): LIVI is a real open‑source 1280×720 touch+D‑pad head unit. CarPlay needs a physical Apple **MFi auth coprocessor** (honest: `carplay.active=false`, not faked). Built a **LIVI deck** that drives the head unit with AxzoraOS apps — `:8002/v1/livi` returns a deck manifest (OpenRoom, God's Eye, Mr Happy, Car Twin, Happy Paisa) + `axzora-apps/livi-deck.html` renders it; terminal gained a `livi` command. | `/v1/livi` returns 5 apps + honest MFi note; `livi-deck.html` served by `:8170`; terminal carplay/livi both work. |
| 2026‑09‑11 | **Fake wallet removed — commerce now settles on the real ledger.** `hp-checkout.js` kept its balance in `localStorage('hp_balance')` and "paid" by decrementing that number; its UPI button ran `setHP(bal()+hp)` and **credited HP on a click with no verification** (unlimited self-minting). `happy-paisa-world.html` was worse: it defaulted to `\|\|2500`, showing every new visitor ₹25,00,000 of money that did not exist, and a `setInterval` invented ATM/UBI/solar transactions every 2.6s into the same log as real ones. Rewrote `hp-checkout.js` onto the `:8787` rail (`/ledger/balance` read, token-gated `/ledger/transfer`, `/wallet/register` identity), **fail-closed** (refuses to pay when the ledger is unreachable rather than guessing); removed the self-credit button (now logs an `UNVERIFIED` claim and credits nothing); removed the fabricated ticker; labelled ATM/withdraw paths **SIMULATED**; added a "NO TRAVEL SUPPLIER IS CONNECTED" notice to the flight/hotel catalogues. Also fixed a real blocker: `send()` only allowed `Content-Type`, so **no browser could ever send the `x-axzora-token` header** — preflight now allows it. | Broke user → `409 insufficient balance`; funded user → `200`, double‑entry consistent (50→49.9999 / 0→0.0001), history records it, reversal restores net zero. Preflight now returns `Access-Control-Allow-Headers: Content-Type, x-axzora-token`. All 5 pages pass an inline-script syntax check. |
| 2026‑09‑11 | **Supervisor consolidation.** Found **4 keepers and 10 grid-watchdogs** running concurrently, all restarting the same services and racing for ports. Root cause: `axzora-grid-watchdog.sh` had **no single-instance lock at all**, so every launch added another permanent copy. Added a pid lock with stale-lock reclaim + `trap`, then killed all duplicates and started exactly one of each. | Before: keepers=4 watchdogs=10. After: keepers=1 watchdogs=1 (locks `.axzora-keeper.lock` → 8664, `grid/.grid-watchdog.lock` → 8665). Keeper auto-healed a killed `:8787` in ~10s. |
| 2026‑09‑06 | **AirLLM (fleet GPU job)**: added an `airllm` job to `axzora-fleet/jobs.json` (`gpu:true`, 4 cores, 16GB) — runs a 70B‑class model on ~4GB VRAM via AirLLM. The **phone orchestrates** it; AirLLM needs a CUDA GPU (VRAM) and does **not** run on the ARM/no‑GPU phone. Bridge `POST /v1/airllm` routes to the GPU worker (honest `NO_GPU_WORKER` when none connected); documented in `PC-WORKER.md`. | coordinator knows airllm (`/api/route/airllm` → 200); `/v1/airllm` returns honest `NO_GPU_WORKER`; GPU workers auto‑get the job via `workerFits(gpu)`. |
| 2026‑09‑06 | **AirLLM app + Mr Happy tool**: added an **AirLLM app** (appId 127, Brain icon) + Mr Happy `airllm` tool + `open_app airllm` so you can invoke the big‑model (70B) path from chat/desktop. Honest: needs a connected GPU fleet worker; the phone orchestrates it, doesn't run it. | OpenRoom built (2m); AirLLM chunk + `airllm` tool served; `/v1/airllm` returns honest no‑GPU‑worker note. |
| 2026‑09‑12 | **CRITICAL: `/ledger/settle` minted unlimited Happy Paisa with no credentials.** Settling an offline voucher credited the recipient and debited **nobody**, required **no token and no admin code** — only a caller-supplied nonce. Anyone could `POST {"uid":<me>,"nonce":<random>,"hp":1000000}` and create money, which made the `x-axzora-token` gate on `/ledger/transfer` pointless (why steal HP when you can mint it). Proved exploitable on a throwaway account: `0 → 5 → 10 HP` with two fresh nonces and no auth. Rewrote it as a **real double-entry settlement**: token-gated, the voucher's issuer must be a registered Axzora ID with sufficient balance, issuer is debited in the same atomic step that credits the recipient, and nonce replay is still refused. | No token → `401 AUTH_REQUIRED`; token + unbacked issuer → `400`; token + funded issuer → `200` with issuer `-2` / recipient `+2` (consistent); same nonce again → `409`; unfunded issuer → `409`. All probe money then removed from `ledger.json`/`wallet.json` with backups and the issuer restored to exactly 50 HP. |
| 2026‑09‑12 | **18.126 — Happy Paisa now settles inside the app.** `LedgerClient.kt` sent **no** `x-axzora-token`, so the app's `transfer` / `stake` / `unstake` / `settle` all returned `401 AUTH_REQUIRED` — HP could be spent in a browser but not in the app. It now fetches the token from the bridge on `:8002` (loopback only), caches it, sends it on every money-moving call, and discards it on `401` so the next call retries. Also pinned `androidx.fragment:fragment:1.5.4`: a transitive `1.0.0 → 1.1.0` sat below the 1.3.0 the ActivityResult APIs need, and lint's `InvalidFragmentVersionForActivityResult` made **`lintVitalRelease` fail every release build** — the APK still packaged, so the build looked successful while actually exiting non-zero. | First fully green `assembleRelease`: `BUILD SUCCESSFUL`, `lintVitalRelease` passed. APK `com.axzora.os` 18.126/206, release-signed; `x-axzora-token`, `127.0.0.1:8002/api/token` and the `401` message all confirmed present in `classes2.dex`. |
| 2026‑09‑12 | **Watchdog hardened + OTA published.** `axzora-grid-watchdog.sh`'s lock was a racy check-then-write, so simultaneously-started watchdogs all passed it: **10 detached copies** were alive with the lock pointing at a dead pid. Replaced with the keeper's **atomic `mkdir` lock** plus an in-loop ownership re-check, and added a **60 s per-service relaunch throttle** (a service that could not stay up under memory pressure was being respawned every 15 s forever, worsening the pressure). | Duplicate watchdog → `already running (pid 21098); exiting`; duplicate keeper → `already running (pid 21095); exiting`; exactly **1 keeper + 1 watchdog** detached, 15/15 services up. OTA `/update/check` → `18.126 / code 206`, `/update/download` → `200`, `Content-Length: 109434912`. |
| 2026‑09‑12 | **Shop Agent now takes Happy Paisa.** `checkout` replied *"Order placed! Total $X. Thank you!"* while doing **nothing** — the file contained no reference to the ledger, no payment, no order record, no stock movement, and it silently emptied the cart. It is now wired to the real `:8787` rail: `checkout` alone is a **priced quote** (in $ *and* HP, stating the conversion); `checkout <axzora-id>` settles a **real** token-gated transfer to `AXZ-MRHP-STOR` and only then clears the cart and records the order. Added `POST /checkout {uid, cart}` (402 when refused) and `GET /orders` (paid orders only). **Fails closed** on insufficient balance, unknown ID, empty cart, self-payment or a missing token — charging nothing, keeping the cart, and reporting the ledger's own error. Mr Happy's `shop` tool gained a `uid` arg (resolved from `/wallet/directory`, the same rule `hp_wallet` uses) and now returns the cart, which it previously discarded — so "add X" then "checkout" actually worked instead of checking out an empty cart. The USD→INR rate is one named constant (`USD_INR`, default 83) because the catalogue is USD-priced while HP is pegged at ₹1000 — it is never hidden and is printed in every quote. | Real payment verified: payer `50 → 49.93443`, merchant `0 → 0.06557` (double-entry consistent), order recorded with ledger balance. All four refusal paths returned `placed:false` with balances untouched; `/checkout` refused → `402`. Test payments reversed, ledger restored to exactly 50/0. Also fixed `build-or.sh`, which ran `"NODE"` (a literal command name) and so died with **exit 127 on every run**. |


| 2026‑09‑15 | **18.128 — the stack now actually stays up.** `KeepAliveService` (the app's foreground service, the only supervisor Android cannot reclaim when it kills the shell app's process group) watched just **two** ports (`:3000`, `:8787`) and **never restarted the keeper or the watchdog**. Consequence: when both supervisors died, **15 of 17 services were unsupervised** and stayed down forever — which is exactly why "servers are self-healing" kept being claimed and kept being false. It now calls `axzora-ensure.sh` every ~30 s (immediately when the core is down), a script that sweeps **all 17 services** *and* **restarts dead supervisors** by checking their lock-dir pids. Logic lives in shell, not Kotlin, so it can be fixed without a 7‑minute APK build. | Tested, not assumed: `:8222` killed → `000`, ensure → `200` logged as `start:8222`. Both supervisors killed → locks went `29314/29315` → `32019/32025`, both ALIVE, logged `keeper-started, watchdog-started`. Healthy stack → ensure is a true no-op (0 log lines). Real recovery run logged `keeper-started, watchdog-started, start:3000, start:4173, start:8090, start:8787, start:8095, start:8222`. APK 18.128/208 `BUILD SUCCESSFUL`, `lintVitalRelease` passed, published to OTA (`/update/check` → 18.128/208, `/update/download` → 200). |
