# AXZORA OS — MR HAPPY AI CORE
## Detailed Technical & Product PRD (v2.0)

> **⚠️ Honesty banner (read first):** This is a **concept / vision specification**, not
> a description of the shipped app. The **shipped AxzoraOS (v18.39)** is a personal
> dashboard + voice assistant + device-control + **demo points wallet**. It does NOT run an
> autonomous AGI, a fleet of hundreds of agents, or a real crypto currency. Words like
> "AGI," "autonomous," "self-evolving," "₹/$ value of HP," and "247 agents" are
> aspirational design goals — NOT shipped features. In the actual app these are honestly
> labelled (e.g. "Mr Happy AI," "ASSISTANT," "demo — not real currency").

> **Mission:** move AxzoraOS from a smartphone launcher to a **self-evolving,
> AI-native operating system** where Mr Happy is not an app but the central
> intelligence layer that connects human interaction, device control, personal
> data, business operations, finance, communication, automation, security and
> creativity.
>
> **Honesty contract (read this first):** this PRD is a *vision*. It is written in
> full detail as a product/engineering specification, but every subsystem is tagged
> with its real status so nothing is over-claimed:
> - **✔ SHIPPED** — built, running on-device today
> - **🔨 BUILDABLE** — achievable on this phone with the current stack (no server)
> - **🔭 SERVER** — needs a GPU/server backend (cannot run on the phone)

---

## 0. System architecture (the target)

```
                  HUMAN
                    │
                    ▼
          ┌───────────────────┐
          │  MR HAPPY AI CORE │  ← the intelligence layer (not an app)
          └─────────┬─────────┘
                    │
   ┌────────────────┼────────────────┐
   │                │                │
 UNDERSTANDING    DECISION        EXECUTION
 ENGINE           ENGINE         AGENTS
 │                │                │
 NLU / context    router /        tool dispatch
 knowledge        planner /       (~40 device tools)
 graph            reasoner        8 sub-agents
                    │                │
                    └───────┬────────┘
                            │
                            ▼
                    LEARNING MEMORY
              (facts / perception / knowledge graph / learned rules)
                            │
                            ▼
                   IMPROVED EXPERIENCE
```

**Flow:** User → Mr Happy Understanding → AI Decision Engine → AI Agent Network →
Execution Layer → Learning Memory → Improved Experience.

---

## 1. MR HAPPY AI CORE — the main intelligence layer

### 1.1 The core interface (home screen)
A live "brain" card replacing the static assistant bar:

```
╔══════════════════════════════════════╗
║         ✦  MR HAPPY AI CORE  ✦       ║
║   STATUS:  ● Online  ● Listening     ║
║   Intelligence Engine: ACTIVE        ║
║   Reasoning: ACTIVE                  ║
║   Voice: READY                       ║
║   Memory: SYNCHRONIZED               ║
║   Capabilities: Device·Apps·Automate ║
║                 Search·Memory·Offline║
║   Power ⚡  Memory 🧠  Devices 📱    ║
║   Privacy 🔒                         ║
╚══════════════════════════════════════╝
```
- **✔ PARTLY SHIPPED (18.36)** — the AI Core glass card (status, capability
  chips, live Power/Memory/Devices/Privacy row) is on the home screen now.
- **🔨 NEXT** — a full-screen "Mr Happy AGI" brain interface showing the module map
  (Reasoning, Vision, Voice, Memory, Planning, Automation, Security, Business,
  Knowledge Graph, Agent Network) with live per-module status.

### 1.2 Core intelligence modules

| Module | Status | Notes |
|---|---|---|
| Reasoning Engine | ✔ SHIPPED | DeepSeek (cloud) + local llama (offline) |
| Voice Intelligence | ✔ SHIPPED | Gemini Live + wake words |
| Memory Engine | ✔ SHIPPED | `Memory` (facts/taught) |
| Perception (realtime) | ✔ SHIPPED | `Perception` ring buffer |
| Self-improvement | ✔ SHIPPED | `LearnedRules` reflection |
| Knowledge Graph | ✔ SHIPPED | `KnowledgeGraph` (entities+links) |
| Agent Network | ✔ SHIPPED | 8 sub-agents + CEO orchestrator |
| Vision Intelligence | 🔨 runnable | a11y `read_screen`; true vision model = 🔭 |
| Planning Engine | 🔨 buildable | CEO decompose/plan; long-horizon = 🔭 |
| Creativity/Coding | 🔭 SERVER | image/3D gen, full app deployment |

---

## 2. The agent network (AI workers)

### 2.1 Employee roster (on-device, bounded)

| Agent | Emoji | Skills | Tools | Status |
|---|---|---|---|---|
| Research | 🔎 | search/facts/analyze | web_search | ✔ |
| Coding | 💻 | code/script/build | run_termux, read_screen | ✔ |
| Marketing | 📣 | campaign/copy/social | web_search | ✔ |
| Finance | 💰 | budget/balance | admin_overview | ✔ |
| Security | 🛡 | audit/threat/privacy | device_status | ✔ |
| Design | 🎨 | visual/poster | open_website | ✔ |
| Customer | 🤝 | support/feedback | send_whatsapp (gated) | ✔ |
| World Builder | 🌍 | simulate/city | WorldEngine | ✔ |

Each agent has **memory, skills, goals, tools, self-evaluation, and task
planning** (the `Agent` interface).

### 2.2 CEO orchestrator
- Routes a natural-language request → understand → reason → decompose → assign →
  execute → aggregate → self-evaluate.
- **✔ SHIPPED** — `CeoAgent` + `CeoDashboard` (control center UI).
- ⚠️ **"247 agents" is a server/fleet concept.** On-device we run a bounded,
  safe 8-agent team. Scaling to hundreds needs a backend.

### 2.3 Auto-routing example
"Prepare my business report" →
1. Research agent: gather sales data
2. Finance agent: summarize analytics
3. Marketing/Design: build a summary
4. Coding: assemble a deliverable file
5. CEO: aggregate + report + memory
> ⚠️ On-device today these are procedural (safe). Real "report generation" with
> charts = 🔭 server + vision/generation models.

---

## 3. Personal memory matrix (the "understand your life" layer)

```
                 USER
           ┌──────┼──────┐
         Work   Life   Goals
         │       │       │
     Projects Contacts Plans
         │
     Preferences
         │
     AI Understanding
```

| Layer | What it stores | Status |
|---|---|---|
| Working | this conversation | ✔ `Brain` history |
| Episodic | "what happened / what I did" | ✔ `Perception` |
| Semantic | "facts about you / the world" | ✔ `Memory` |
| Knowledge graph | entities + typed links | ✔ `KnowledgeGraph` |
| Procedural | "how to do X" (learned) | ✔ `LearnedRules` |

- **✔ SHIPPED.** This is the on-device stand-in for a vector-DB + knowledge graph.
- **🔭 UPGRADE** — real *embeddings* (semantic recall) + procedural recall need a
  model with embedding support (phone or server).

---

## 4. CEO Mode — digital CEO command center

```
╔══════════════════════════════════╗
║      MR HAPPY CEO MODE            ║
║  Executive stats                  ║
║  Active Agents: 8/8               ║
║  Tasks Dispatched: N              ║
║  Tasks Running: N                 ║
║  World: <world name> (sim)        ║
║  ⚙ Voice command: "run [task]"    ║
╚══════════════════════════════════╝
```
- **✔ SHIPPED** — `CeoDashboard`.
- **🔭 SERVER** — real Revenue/Growth/Customers/Operations analytics + business
  intelligence needs a business-data backend.

---

## 5. Happy Paisa AI — financial operating system

| Capability | Status |
|---|---|
| Balance (HP) | ✔ |
| INR + USD price | ✔ (₹1000 / $11.55) |
| Transfer / receive | ✔ |
| Mint (admin) | ✔ |
| Staking (6% APR, 30-day lock) | ✔ |
| Ledger history | ✔ |
| AI finance advisor (spending/savings/prediction) | 🔨 buildable |
| Investment/tax alerts | 🔭 server |

**🔨 NEXT ON-DEVICE:** on-device spending summary + "financial health" score +
a simple savings prediction from ledger history. This is achievable today.

---

## 6. Offline private AI mode

- **✔ SHIPPED (18.31).** Auto-fallback: local llama → cloud DeepSeek. Works with no
  internet; sensitive tools gated; code/key never in APK.

---

## 7. Axzora Sentinel — security AI

| Capability | Status |
|---|---|
| App Shield (hide dev/debug/accessibility per app) | ✔ (18.33–18.34) |
| No secrets in APK (admin code, AI key) | ✔ |
| Sensitive-tool confirmation | ✔ |
| Threat/monitoring AI, permission audit, fraud detection | 🔭 SERVER |

---

## 8. Omni-device control (TV, car, smart home, robots)

- **🔭 SERVER / integrations.** Requires device protocols & a backend. Not this phone.

---

## 9. AI app generator / company builder

- **🔭 SERVER.** Needs a real coding model + a deploy target. On-device Coding agent
  can plan/scaffold but not ship a production app.

---

## 10. Visual interface (the "holographic" feel)

| Feature | Status |
|---|---|
| Dark premium theme + gold accent | ✔ |
| Glassmorphism cards | ✔ |
| Animated AI pulse / status | ✔ (18.36) |
| 3D AI orb / waveform | 🔨 buildable in Compose |
| AR / full 3D | 🔭 GPU |

---

## 11. Advanced voice intelligence

- **✔** Gemini Live realtime + wake word + DeepSeek tool-calling.
- Conversation ("I have a meeting tomorrow" → auto-create event) — 🔨 buildable by
  wiring the AI to calendar/alarm tools (already available).

---

## 12. Life / automation engine

- **🔨 buildable**: "if X then Y" rules on-device (like `Watches` but richer).
- **✔** `Watches` (watch url for keyword) already ships as a bounded monitor.

---

## 13. Navigation system (the "OS" feel)

Target nav: **⌂ Home · ✦ Mr Happy AI · ◈ CEO Center · ₹ Happy Paisa · ◉ Devices ·
♧ Family · ⚙ System.**
- **🔨 buildable** — a proper bottom navigation bar replacing the current dock. This
  is a clear, high-value next step.

---

## 14. Delivery roadmap (real, ordered)

| Phase | Deliverable | Status |
|---|---|---|
| 1 | Launcher + assistant + memory | ✔ |
| 2 | DeepSeek brain + on-device model + offline | ✔ |
| 3 | Admin tools + AI CEO + World Engine | ✔ |
| 4 | Staking + USD + security fix + App Shield | ✔ |
| 5 | **AI-native home (AGI Core card + greeting)** | 🔨 18.36 (building) |
| 6 | Bottom navigation + Mr Happy AGI screen | 🔨 next |
| 7 | AI finance advisor (on-device) | 🔨 next |
| 8 | Agent fleet + business analytics + 3D | 🔭 server |

---

## 15. Final product definition

> **The first AI-native personal operating system.**
>
> Not a launcher. Not a chatbot. Not a wallet app. It is:
> - ✅ Personal AI brain
> - ✅ Digital CEO
> - ✅ Device controller
> - ✅ Business operator (on-device subset)
> - ✅ Financial intelligence
> - ✅ Automation engine
> - ✅ Private assistant ecosystem
>
> **MR HAPPY AGI CORE** — Observe → Understand → Reason → Plan → Execute → Learn →
> Evolve.
>
> **"An AI that doesn't run on your phone. An AI that runs your phone."**
