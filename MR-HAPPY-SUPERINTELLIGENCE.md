# Mr Happy — Real-Time AI Architecture

> **⚠️ Honesty banner (read first):** The title previously said "Superintelligence."
> That is a **design aspiration**, not what the shipped app does. AxzoraOS **v18.39**
> runs a real on-device + cloud *assistant* with tools, memory, and a small agent
> system — it is **not** an autonomous superintelligence. Terms like "superintelligence,"
> "self-improving AGI," and "learn reflect evolve" are future goals. The actual app is
> honestly labelled ("Mr Happy AI," "ASSISTANT," modules marked Active vs Planned).

**Vision:** not a chatbot, but a persistent, on-person **agentic intelligence** that perceives the world through the device, reasons across multiple models, decides with tools, remembers continuously, and *acts* — all in real time.

This doc is in two parts and is explicit about what is **built today** versus the **target architecture**. The framing is ambitious; the facts are not inflated.

---

## PART 1 — Built today (this is real, it runs)

### 1. Perceive
- `GeminiLive.java` — realtime streaming voice session (listen + speak)
- `VoskWake.java` / `PorcupineWake.java` — wake-word detection
- `HappyReddyA11y.java` — accessibility service → **reads the actual screen**, taps/typing/swiping
- `PhoneFacts.kt` — device context (model, OS, sensors)
- Satellite sensors: location (`Graph.kt`), traffic (`Traffic.kt`)

### 2. Reason (multi-model routing, `Brain.kt`)
A **cascade**, tried in order, first that answers wins:
1. **On-device LLM** (`LocalLlm.kt` / llama.cpp, OpenAI-compatible) — no network, private
2. **LAN brain** (`localBrainUrl`) — a PC on the home network
3. **Cloud DeepSeek** (`DeepSeek.kt`) — smartest, tool-calling
`DeepSeek.kt` runs an agent loop: model → calls a tool → result fed back → repeats (≤8 rounds).

### 3. Decide & Act (the tool framework, `DeviceActions.java`)
~40 tools: open app, calls, WhatsApp/SMS, navigation, media, alarms, calendar, tap/type/scroll, `web_search`, `weather`, `traffic`, chess, and the **admin tools** (overview/notify/mint).
- `SENSITIVE_TOOLS` → **yes/no confirmation** before acting (never acts silently)
- `RESTRICTED_TOOLS` → blocked throughly (e.g. `run_termux` in restricted mode)
- **Admin tools** gated by stored code + server seat; the code never reaches the model.

### 4. Remember (continuous, `Memory.kt`)
- `remember(subject, value)` / `recall(subject)` — semantic facts
- `teach(question, answer)` — shape-matched taught answers
- `note(line)` / `recent()` — episodic log
- Persisted in DataStore.

### 5. Run autonomously (`Watches.kt`)
`watch <url> for <keyword>` — background monitors that check and **notify** you. Bounded (max 5 watches, 30 checks), user-cancellable. Plus `CommandEngine.kt` live-context (commute, device queries) and the floating bubble (`FloatingService.kt` / `FloatingUi.kt`).

### 6. Feedback loop
Every tool call returns a real result string back to the model (`tool → tool response → model`), so the intelligence **sees the outcome and corrects** — the seed of self-improvement.

---

## PART 2 — The target superintelligence architecture (the "advanced" design)

This is the reference design to grow into. Each layer labels the entry point in today's code.

### A. Realtime perception bus
```
wake word → audio stream → ASR (Vosk) ─┐
a11y screen stream                     → Realtime Context Buffer (ring buffer, ~2 min)
sensor/location/traffic               ─┘
                    │
                    ▼
        PerceptualState { lastScreen, lastUtterance, lastLocation, lastVitals, time }
```
- **Goal:** a continuously-updated perception state the model always sees — not just "current screen" but "what just happened."
- Today: `HappyReddyA11y.readScreen`, `GeminiLive`, session state. Target: a unified context buffer flushed into the prompt every turn.

### B. Hierarchical reasoner (a "guild" of models)
```
        ┌─ Router (cheap/fast) ─ selects the best agent ─┐
        ▼                                                 ▼
  Orchestrator (DeepSeek, tool-calling)   Specialist (local, offline/private)
        │ tool calls                          │
        ▼                                      ▼
  Tool Layer (DeviceActions, ~40 tools)    Local brain (llama.cpp)
        │
        ▼
  Verifier (checks the result is sane / safe)
```
- **Goal:** route each task to the model whose cost/latency/quality fits, with a verifier in front of anything that acts.
- Today: the `Brain` cascade is the embryo of the router.

### C. Agentic loop with reflection
```
Perceive → Reason → Act → Observe → Reflect
                                  │
                                  ▼
                     LearnedRuleStore (what worked, persisted)
```
- **Goal:** after each action, the system appends *"I did X, the result was Y"* and updates its own rule store so next time it's faster/better. This is genuine self-improvement, bounded and audit-able.
- Today: the `tool → response` loop already observes outcomes; reflection + persisted learning is the delta.

### D. Four-tier memory (the superintelligence substrate)
| Tier | What | Today's seed |
|------|------|--------------|
| **Working** | this conversation (hot) | `Brain` history |
| **Episodic** | "what happened / what I did" | `Memory.note()` |
| **Semantic** | "facts about you / the world" | `Memory.remember()/recall()` |
| **Procedural** | "how to do X" (learned rules) | *target* — `LearnedRuleStore` |
- **Goal:** hybrid RAG — semantic (embedding) recall + procedural (learned action) recall, so it remembers both *facts* and *how* to act.
- Today: keyword/semantic `Memory`; embeddings + procedural recall is the delta.

### E. Autonomous agency (guardrails, not free rein)
- `Watches` grows into a **goal-oriented task scheduler**: "watch X, and when Y, do Z."
- Every autonomous action is: **bounded** (cap), **attributed** (logged), **reversible** where possible, and **operator-gated** if sensitive.
- Today: `Watches.kt` + the sensitive-tool gate is exactly this pattern.

### F. The safety contract (non-negotiable)
1. **Nothing acts without visibility** for `SENSITIVE_TOOLS` / admin tools — confirmation first.
2. **The admin code never goes to a model** — injected on-device, re-verified server-side.
3. **No unbounded loops** — tool rounds (≤8), watches (≤5/≤30), all capped.
4. **Everything logged** — every tool call and result is attributable.
5. **Graceful degradation** — no cloud, no PC, no signal: still answers from the on-device brain.

---

## The one-line architecture
```
[Sense] → [in-memory perception state] → [model router → orchestrator → tools]
    → [observe result] → [reflect & learn] → [remember: episodic/semantic/procedural]
    → [autonomous watchers] — all behind a confirmation + audit safety gate
```

## Security notes
- **Admin code**: server + on-device wallet only; never in the APK; re-checked per call.
- **DeepSeek API key**: currently baked into `Config.kt` as a fallback — **rotate it** and move it behind a server-side proxy for any shared build.
- **AI control gated**: sensitive tools always ask; admin tools only offered when a code is stored.
