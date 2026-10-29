# Axzora OS — Mr Happy AI (Roadmap)

> **⚠️ Honesty banner (read first):** This document is a **concept / future vision**.
> It is NOT a description of what the shipped app does. The **shipped AxzoraOS app
> (v18.39)** is a personal dashboard/launcher with a voice assistant, device control,
> automation shortcuts, and a **demo points wallet** — it does **not** run an
> autonomous AGI, does **not** control a fleet of hundreds of agents, and Happy Paisa
> is **in-app demo points, not a real currency**. Words like "AGI," "autonomous,"
> "self-evolving," and any US$/INR value are aspirational ideas, not shipped features.
> Where terminology appears that over-claims, treat it as a design goal to be reached
> honestly later, not something the app claims today.

> **Vision:** move from a smartphone launcher into a self-evolving AI-native OS where
> Mr Happy is the central intelligence layer, not an app.
>
> **Honesty note (read first):** this is a *vision* PRD. A large share of it cannot
> run on the phone today. This document marks each item as **✔ shipped**, **🔨
> buildable on-device**, or **🔭 needs a server/GPU** — so the roadmap is real, not
> a slide deck for a machine the phone can't be.

---

## Status legend
- **✔ SHIPPED** — built and running on-device
- **🔨 BUILDABLE** — achievable on the phone with the current stack (no server)
- **🔭 SERVER** — needs a GPU/server (can't run on this phone)

---

## 1. Mr Happy AI Core (main intelligence) — 🔨 partly done
Home screen now has a live **MR HAPPY AI CORE** glass card (Online / Listening,
capability chips, Power/Memory/Devices/Privacy status) + a personal greeting
dashboard. The full "brain interface" screen is the next step.

| Subsystem | Status |
|---|---|
| Reasoning engine | ✔ DeepSeek + on-device llama |
| Voice intelligence | ✔ Gemini + wake words |
| Memory engine | ✔ Memory / Perception / LearnedRules |
| Knowledge graph | ✔ `KnowledgeGraph` |
| Agent network (8 agents) | ✔ `SubAgents` + CEO orchestrator |
| Vision intelligence | 🔨 a11y screen read exists; real vision model = server |
| Planning engine | 🔨 CEO decompose/plan; long-horizon = server |
| Creativity engine | 🔭 server (image/3D gen) |

## 2. Personal AI memory matrix — ✔ shipped
`KnowledgeGraph` (entities + links) + `Memory` (facts/taught) + `Perception`
(realtime) + `LearnedRules` (self-improve). This IS the "understand the user's
digital life" substrate.

## 3. Autonomous AI agent system — ✔ shipped (on-device version)
8 agents (Research, Coding, Marketing, Finance, Security, Design, Customer, World
Builder), each with memory/skills/goals/self-eval/planning, routed by the CEO.
- ⚠️ "247 agents" needs a server fleet. On-device: 8, bounded & safe.

## 4. Mr Happy CEO mode — ✔ shipped
`CeoAgent` orchestrator + `CeoDashboard` control center: executive stats, team,
world map, voice command, LLM briefing.
- 🔭 Revenue/customers/growth analytics needs the server's business data.

## 5. Happy Paisa AI — ✔ shipped (fintech base) + 🔨 more
Wallet has balance, INR + USD, transfers, mint, **staking (6% APR/30-day)**, ledger.
- 🔨 Financial advisor / spending analysis is a natural next on-device layer.

## 6. Offline private AI mode — ✔ shipped (18.31)
Auto-fallback chain: local llama → cloud DeepSeek; works with no internet.

## 7. Omni-device control (TV/car/robots/IoT) — 🔭 SERVER
Needs device integrations. Not this phone.

## 8. AI app generator / company builder — 🔭 SERVER
Needs a real coding model + a backend to deploy to. On-device Coding agent can
scaffold/plan, not ship full apps.

## 9. Holographic 3D orb / AR / holographic cards — 🔨 partly
A live animated orb + waveforms are buildable in Compose; full 3D/AR = GPU.

## 10. Security Sentinel — 🔨 partly
App Shield (hide dev/debug/accessibility per app) ✔. Full threat/monitoring AI =
server.

---

## The build order that's real
1. ✔ AI Core home card + greeting (this build, 18.36)
2. 🔨 Mr Happy AGI "brain" full screen (module map + live status)
3. 🔨 AI Finance advisor (spending summary + prediction on-device)
4. 🔨 Animated AI orb / waveform around the core
5. 🔭 Agent fleet + business analytics (server)

---

## Final identity
> "An AI that doesn't run on your phone. An AI that runs your phone."

**Powered by:** Mr Happy AGI Core — Observe → Understand → Think → Plan → Execute
→ Learn → Evolve.
