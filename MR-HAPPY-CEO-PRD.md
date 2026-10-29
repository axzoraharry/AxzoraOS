# Mr Happy AI — Product Requirements Document (PRD)

> **⚠️ Honesty banner (read first):** The "AI CEO" is a **concept / design goal**, not
> how the shipped app describes itself. AxzoraOS **v18.39** is a personal dashboard +
> assistant + device-control + **demo points wallet**. The on-device agent system is a
> small, bounded, deterministic tool for planning/routing — it does **not** run an
> autonomous company, real business analytics (revenue/customers), or a real currency.
> Revenue/growth figures and "runs your business" claims are aspirational, not shipped.

> **One-line vision:** an autonomous AI CEO that lives on your device, runs digital
> operations, manages AI employees, builds simulated worlds, and speaks to you
> naturally.
>
> **Honesty note (read first):** everything in **"On-device (ships today)"** is real
> and runs on the phone. Everything in **"Server/Cloud (roadmap)"** needs computing
> this phone physically cannot do (GPU 3D, model training, fleet orchestration).
> This PRD separates the two so the product is honest, shippable, and genuinely
> impressive — not a slide deck for a machine this phone can't be.

---

## 1. Product pillars

| Pillar | On-device (real today) | Server/cloud (roadmap) |
|---|---|---|
| **Voice Brain** | Real-time speech + wake word + on-device + cloud LLM | Whisper-class multilingual STT, TTS |
| **Executive AI** | CEO orchestrator + 8 sub-agents who can plan & act | LangGraph/AutoGen fleet, model routing, long-horizon planning |
| **World Engine** | Procedural **Happy World** simulator (city, citizens, economy, weather) | Unreal/Unity/Blender, GPU 3D generation |
| **Digital CEO dashboard** | Live agent counts, tasks, world sim, voice | Revenue/security telemetry dashboards |
| **Phone-OS hook** | App/file/notification/smart-device control | Full automation engine on all platforms |

---

## 2. Core architecture (on-device)

```
                       MR HAPPY AI CEO
                                  |
        ┌──────────────┬──────────┴──────────┐
        |              |                      |
   Voice Brain     Executive AI           World Engine
   (existing)      (CEO + 8 agents)        (procedural)
        |              |                      |
   Speech↔DeepSeek  Decision Maker         Happy World sim
   Phone control     AI employees          City/citizens/economy
```

## 3. The executive team (8 sub-agents)

Each agent has **memory, skills, goals, tools, self-evaluation, task planning**:

| Agent | Skills | Tools it can use |
|---|---|---|
| **CEO** (orchestrator) | Break a goal into a plan, route to specialists, aggregate | all |
| **Research Agent** | look things up, summarize | `web_search`, `open_website` |
| **Coding Agent** | read/write files, run commands | `run_termux`, `read_screen` |
| **Marketing Agent** | plan campaigns, write copy | `web_search`, `open_game` (demo) |
| **Finance Agent** | balance, budgets | `admin_overview` |
| **Security Agent** | watch for threats, audits | `web_search`, `device_status` |
| **Design Agent** | generate visual specs (ASCII/HTML) | `open_website` |
| **Customer Agent** | respond to feedback | `send_whatsapp` (gated) |
| **World Builder Agent** | build/simulate worlds | `WorldEngine` |

## 4. Real-time voice command flow

```
User: "Mr Happy create a new cafe marketing campaign"
  → understand → CEO decomposes into plan → Marketing + Design + Finance agents
  → generate copy + poster spec + budget → schedule → report back by voice
```
Voice, reasoning, TTS, and streaming are **already on-device** (see §7).

## 5. Happy World Engine (procedural, on-device)

Input: `create a futuristic smart city with AI citizens, hospitals, shops, AVs`

Output (deterministic, seeded, lives as a `WorldState` you can inspect):
- **terrain** — grid + zones (residential/commercial/industrial/green/water)
- **buildings** — per-zone generation
- **characters** — AI citizens with roles, needs, homes, jobs
- **economy** — money, shops, job market, daily transactions
- **weather** — seeded daily cycle
- **simulation** — a tick loop that advances the city

This is a *real* simulator with data you can read and step — not a fake. It is
procedural and text/data-based because the phone has no GPU; the same `WorldState`
JSON is what a GPU renderer would consume on a server later.

## 6. Digital CEO Control Center (dashboard)

```
╔══════════════════════════════╗
║  MR HAPPY CEO                ║
╠══════════════════════════════╣
║ Active Agents: 8/8           ║
║ Running Tasks: N             ║
║ World: HappyWorld v1 · day N ║
║ Economy: ₪ balance           ║
║ Weather: ☀ / 🌧 / ❄          ║
║ Voice: live                  ║
╚══════════════════════════════╝
```

## 7. What already exists (reuse, don't rebuild)

- **Voice**: `GeminiLive`, wake words (`VoskWake`), on-device + cloud brain (`Brain`)
- **Tool execution**: `DeviceActions` (~40 tools) with sensitive/restricted gates
- **Memory**: `Memory` (facts/taught), `Perception` (realtime), `LearnedRules` (self-improve)
- **Security**: admin code never in APK; every sensitive tool confirms

## 8. Self-improving loop (on-device, already started)

`Observe → Analyze → Learn → Improve` — implemented via `LearnedRules` (records
"What worked"), `Perception` (realtime context), and the reflection hook in the
DeepSeek loop.

## 9. Tech stack — honest split

| Layer | Runtime |
|---|---|
| Mobile brain | **Kotlin** (this app), Compose UI |
| On-device LLM | llama.cpp (`LocalLlm`) |
| Cloud reasoning | DeepSeek (OpenAI-compatible) via `Brain`/`DeepSeek` |
| World engine | **Procedural Kotlin** (no GPU needed) |
| AI backend (fleet, GPU, RAG) | Python + FastAPI + LangGraph + vector DB (a real server) |
| Infra (Docker/K8s/GPU) | **server only** — not this phone |

## 10. Development roadmap

| Phase | Delivered | Status |
|---|---|---|
| 1. Mr Happy assistant | voice, memory, app control, personal tasks | ✅ shipped |
| 2. AI CEO | 8 sub-agents, business automation, reports | ✅ shipped |
| 3. Happy World Engine | procedural city + citizens + economy + sim | ✅ shipped |
| 3.5 Advanced (18.29) | LLM reasoning per agent, knowledge graph, citizen routines, ASCII map, world persistence | ✅ shipped |
| 4. Autonomous super-agent | creates companies, runs projects, builds software | 🔭 roadmap (server+gpu) |

## 11. What's implemented (final)

1. `ceo/Agent.kt` — agent abstraction (memory, skills, goals, tools, self-eval, plan)
2. `ceo/SubAgents.kt` — the 8 concrete agents
3. `ceo/CeoAgent.kt` — orchestrator (understand → reason → decompose → route → run → evaluate)
4. `ceo/Reasoner.kt` — real LLM reasoning (DeepSeek-first, local-llama fallback)
5. `ceo/KnowledgeGraph.kt` — persistent shared world-memory (entities + links)
6. `ceo/WorldEngine.kt` + `WorldStore` — procedural world + citizen routines + economy + weather + ASCII map + persistence
7. `ceo/CeoDashboard.kt` — the Compose control center (stats, team, world map, voice command)
8. Wired into MainActivity → AI Agents → "Mr Happy AI CEO"

---

> **The final product:** *"An AI CEO living in your phone that can think, speak, build,
> manage, simulate worlds, and operate digital businesses."*
> — this build delivers the **think + speak + manage + simulate** parts on-device;
> the **build 3D worlds + run remote fleets** parts land on a server later.
