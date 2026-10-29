# AXZORA OS — Frontend Architecture & Code Reference

> **What this is:** a deep, measured technical reference for the **frontend** of
> AxzoraOS — a Jetpack-Compose Android launcher/wallet/assistant system. It is written
> to be *read by a developer*, with real file/line measurements, layer diagrams, data
> flow, and an honest per-feature status. It is not a slide deck.
>
> **Version 18.41** (code 121) · ~29,400 lines of source.

> **⚠️ Honesty banner (read first):** This is a launcher + voice assistant +
> device-control + **demo points** wallet. It is **not** an autonomous AGI and Happy
> Paisa is **not** real currency. Every feature below is tagged ✔ built / 🔨 partial /
> 🔭 concept so nothing over-claims.

---

## 1. Repository layout (measured)

```
AxzoraOS/
├── app/src/main/java/com/axzora/os/     ← the frontend (this doc)
├── app/src/main/java/com/axzora/happyreddy/  ← assistant (Java+ Kotlin mix)
├── server/server.js                    ← backend (~1,860 lines)
├── build-on-device.sh                  ← on-device build env
├── server/publish-update.sh            ← OTA publish pipeline
└── *.md                                ← PRDs / roadmap / this doc
```

### Frontend package → size (real counts)

| Package | Files | Lines | Role |
|---|---|---|---|
| `ui/` | 6 | 926 | design system (theme, fx, responsive, type) |
| `assistant/` | 14 | 2,947 | brain, command engine, memory, perception, self-learn |
| `ceo/` | 8 | 1,330 | agents, orchestrator, reasoner, knowledge graph, world, AGI UI |
| `wallet/` | 11 | 1,541 | ledger client, store, finance advisor, QR, currency |
| `call/` | 11 | 3,327 | WebRTC/WS calls (largest feature) |
| `nearby/` | 1 | 732 | Google Nearby offline P2P |
| `location/` | 4 | 377 | family map / graph |
| `openroom/` | 2 | 324 | WebView wrapper + shell auto-detect |
| `launcher/` | 1 | 162 | installed-app list + icons |
| `data/` | 1 | 213 | DataStore settings |
| `appshield/` | 1 | 189 | hide dev/debug settings per app |
| `games/` `chat/` `pay/` `sources/` `live/` `update/` `devices/` `net/` | 15 | 1,492 | |
| `happyreddy/` | 15 | ~4,000 | the assistant (voice + tool dispatcher) |

---

## 2. Architecture — 3 layers

```
┌─────────────────────────────────────────────────────────────┐
│  PRESENTATION (Compose)                                      │
│  MainActivity (single-activity) → Screen enum → screens      │
│  Compose state: remember/mutableStateOf/StateFlow            │
│  Fx (revealFrom, pressBounce, AuroraBackground)              │
├─────────────────────────────────────────────────────────────┤
│  DOMAIN (the "intelligence")                                 │
│  assistant/  brain · command · memory · perception · rules   │
│  ceo/        agents · orchestrator · reasoner · knowledge    │
│  wallet/     ledger · finance advisor · store                │
│  appshield/  secure-settings toggler                         │
├─────────────────────────────────────────────────────────────┤
│  DATA / IO                                                  │
│  data/  SettingsStore (DataStore) · wallet/ WalletStore      │
│  net/   ServerFinder · wallet/ LedgerClient · call/ WsClient │
│  update/ Updater (OTA) · launcher/ AppRepository             │
└─────────────────────────────────────────────────────────────┘
```

**Single-activity** shell (`MainActivity`) owns `screen` state; `AnimatedContent`
crossfades. This keeps one nav model and avoids deep intent stacks.

---

## 3. Navigation model

```
Screen enum (23 routes)
  HOME · DRAWER · CHAT · VOICE · LOCATION · SETTINGS · PAY · MESSAGES
  FAMILYCHAT · DM · WALLET · PEOPLE · NEARBY · ADMIN · PERMISSIONS · STYLE
  DEVICES · AGENTS · LIVE · LICENSES · CEO · AGI

MainActivity.kt
 ├── var screen by remember { mutableStateOf(Screen.HOME) }
 ├── BackHandler → screen = Screen.HOME
 ├── CompositionLocalProvider(LocalAnimLevel, LocalAnimSpeed)
 │    └── AnimatedContent(targetState=screen){ current → when(current){ … } }
 └── Column
      ├── AnimatedContent(...).weight(1f)      ← the active screen
      └── BottomNavBar(current, onSelect)      ← persistent bar (main screens only)
```

- **BottomNavBar** tabs: Home · AI (AGI) · CEO · Wallet · Devices · System
- Showed only on "shell" screens; drawer/onboarding are full-screen without it.

---

## 4. Home screen component stack

```
AuroraBackground(accent, dark, atmosphere)
 └─ Column (swipe-up → DRAWER via detectVerticalDragGestures)
     ├─ ClockBlock                     ui/Clock
     ├─ MR HAPPY AI CORE (glass card)  status, chips, live row
     ├─ Greeting + daily context card  context chips (weather/battery/messages/tasks)
     ├─ QuickChip grid (8 chips)       Devices·Family map·Offline·UPI·OpenRoom·Mr Happy·Live·Sources·Games
     ├─ App grid (LazyVerticalGrid)    AppInfo tiles; favorites/essentials
     └─ Dock (7 items)                 Phone·SMS·AI Core·People·Chats·Wallet·Settings
```

The AI Core card pulls **live data** (battery % via `BatteryManager`, an infinite
transition pulse, capability chips). It's intentionally a *status* surface, not links.

---

## 5. Design system (`ui/`)

| File | Content |
|---|---|
| `Theme.kt` | `AccentPresets` (4 palettes, default amber/gold), `darkColorScheme`/`lightColorScheme`, system dark detection |
| `Style.kt` | style presets (theme style enum) |
| `Type.kt` | type scale / fonts |
| `Fx.kt` | animations — `Modifier.revealFrom`, `Modifier.pressBounce`, `AuroraBackground`, `GlassCard` |
| `Responsive.kt` | `dim.*` (grid columns, dock icon, bar height), adaptive sizing |
| `Clock.kt` | home clock face component |

**Design tokens:** dark = pitch `#000000`; accent amber; glass = `surface.copy(alpha=.5–.6)` +
`border` + `RoundedCornerShape`; text-on-amber forced dark (contrast).

---

## 6. Feature packages — deep dive

### `ceo/` (agent system, 1,330 lines)
| File | Role |
|---|---|
| `Agent.kt` | interface: `id/name/skills/goal/memory/work/evaluate/plan` |
| `SubAgents.kt` | 8 workers (Research, Coding, Marketing, Finance, Security, Design, Customer, World Builder) + `route()` |
| `CeoAgent.kt` | orchestrator (understand→reason→decompose→route→run→evaluate) |
| `Reasoner.kt` | LLM reasoning via `/ai/deepseek` proxy (key server-side) |
| `KnowledgeGraph.kt` | persistent entities + typed links |
| `WorldEngine.kt` + `WorldStore` | procedural world, citizen routines, economy, ASCII map |
| `MrHappyAgiScreen.kt` | the brain screen (pulsing orb, Active/Planned modules, agency, world) |
| `CeoDashboard.kt` | control-center UI |

### `assistant/` (the intelligence, 2,947 lines)
Brain (model router: local→on-device→cloud) · CommandEngine (on-device intent parser,
live context, `watch` commands) · Memory (facts/taught) · Perception (realtime ring
buffer) · LearnedRules (self-improvement) · LocalLlm (llama.cpp) · Conversation ·
FloatingService/Ui (bubble) · VoiceController · PhoneFacts · Traffic · Watches.

### `wallet/` (1,541 lines)
WalletStore (DataStore: balance, txns, admin) · LedgerClient (server ledger + staking) ·
FinanceAdvisor (on-device score, spending categories, savings projection) ·
AdminNotify · WalletNet · Currency (multi-currency formatting) · AxzoraId · QrCode ·
Coupons · OfflineTransfer · WalletInbox.

### `call/` (3,327 lines — largest)
CallManager (state machine) · CallService (foreground) · CallSignaling (WS) · WsClient ·
GroupCall · PcmAudio (audio) · VideoView · RelayVoice · Ringer · CallBus (UI hooks) ·
CallDiagnostics.

### `appshield/` (189 lines)
`AppShield` — snapshot→hide→launch→revert of dev/debug/accessibility settings. No root;
needs `WRITE_SECURE_SETTINGS` once (ADB/Shizuku).

---

## 7. Data flow (two key paths)

**Chat/question:**
```
User text → CommandEngine.ask()
  → handle() local rules → traffic → deviceHub → brain.ask() → model
  → Brain tries localBrain → onDeviceBrain → /ai/chat (server)
  → reply → Perception.record("agent") → UI
```

**Wallet balance:**
```
WalletStore.state (DataStore) → BalanceCard
  LaunchedEffect → LedgerClient.balance(uid) → if differs → syncBalance(server)
  Transfer → LedgerClient.transfer → ledger.post both sides + inbox notify
  Staking → staking()/stake()/unstake() → server ledger
```

**Finance advisor (fully on-device):**
```
FinanceAdvisor.analyze(balance, txns)
  → income/spend in 30-day window → saveRatio → healthScore(0..100)
  → category buckets (bills/food/travel/etc) → savings projection → insights
```

---

## 8. State management

- **Compose** `remember`/`mutableStateOf` for UI-local (screens)
- **StateFlow** for cross-screen live data (Nearby peers/messages, Recent apps, settings)
- **DataStore** for durable prefs (`SettingsStore` via `dataStore`, `walletStore`,
  `perceptionStore`, `learnedStore`, `kgStore`, `worldStore`)
- **SharedPreferences** for quick key-value (recent apps, AI options, app shield session)
- **Dispatchers.IO** + `runBlocking` bridges for Java→Kotlin suspend calls

---

## 9. Honest feature matrix

| Feature | Status | Notes |
|---|---|---|
| Launcher + home + grid | ✔ | Favorites, swipe-up drawer, essentials |
| Voice assistant (Mr Happy) | ✔ | Gemini + DeepSeek, wake words |
| Device control (~40 tools) | ✔ | open/call/alarm/tap/type/status |
| DeepSeek brain + offline | ✔ | auto-fallback local→cloud |
| Agents + CEO + world sim | ✔ | deterministic, on-device |
| Knowledge graph + memory | ✔ | perception, learned rules |
| Staking + finance advisor | ✔ | demo points, on-device score |
| App Shield | ✔ | hide dev/debug per app |
| OTA updates | ✔ | server-driven |
| "Autonomous AGI" | 🔭 | aspiration — honesty-labelled |
| Real-currency HP | 🔭 | needs blockchain/licensing — **not claimed** |
| Fleet of 247 agents | 🔭 | needs a server/GPU |

---

## 10. Build & ship

```
bash build-on-device.sh :app:assembleRelease
  # JDK 17, native aapt2, kotlin in-process, tmpdir
bash server/publish-update.sh "<notes>"
  # build release → server/update/axzoraos-$VN.apk → update.json
  # app polls /update/check on launch → offers OTA
```

---

## 11. Docs
`CODE-OVERVIEW.md` · `MR-HAPPY-AGI-CORE-PRD.md` · `MR-HAPPY-AGI-ROADMAP.md` ·
`MR-HAPPY-CEO-PRD.md` · `MR-HAPPY-SUPERINTELLIGENCE.md` · `BUILD.md` · `README.md`
