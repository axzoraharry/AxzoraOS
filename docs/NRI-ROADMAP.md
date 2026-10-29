# AxzoraOS Neural Reality Interface (NRI) — Implementation Roadmap

This maps the NRI v3 vision to the actual AxzoraOS codebase, then lays out a
**phased build plan** grounded in what exists today.

## 1. What's already real (baseline)

| NRI pillar | Implemented by |
|---|---|
| Voice intelligence | `assistant/VoiceController`, `Perception`, vosk ASR + porcupine wake-word (`jniLibs/*.so`) |
| AI reasoning | `assistant/Brain`, `LocalLlm`; `ceo/` (Agent, Reasoner, SubAgents, WorldEngine, KnowledgeGraph) |
| Local model brain | `Brain.ask()` → `LocalLlm` (MediaPipe), LAN brain (`:8080` llama-server), on-device (`:9095`) |
| Behavioral learning | `assistant/Memory`, `LearnedRules`, `ScenarioEngine`, `PhoneFacts` |
| Context awareness | `Perception`, `PhoneFacts`, `FloatingUi` |
| OS autonomous action | `assistant/CommandEngine`, `DeviceControl`, `happyreddy/DeviceActions` (Gemini tool calls) |
| Launcher / OS shell | `MainActivity`, `launcher/`, `ui/`, `appshield/` |

**Not in the code (vision-only):** gesture/hand tracking, spatial/AR, gesture
biometrics, AirScript, HoloControl/Quantum Drag, physics sim, hardware.

## 2. Phased plan

### P0 — Strengthen the AI core (do first, low cost, high value)
- **Unify the brain rail:** make `Brain.ask()` order and fallback explicit in
  Settings (LLM server, Ollama, on-device, cloud) and show which rail answered.
- **Stateful memory graph:** extend `Memory`/`KnowledgeGraph` into a
  persistent **personal graph** (projects ↔ people ↔ preferences ↔ history),
  queryable from `Brain` — this is the "Neural Memory Graph" core.
- **Perception context pack:** enrich `Perception`/`PhoneFacts` to feed screen,
  time, location, and recent apps into the prompt each turn.
- **Autonomous workflow runner:** add a `WorkflowEngine` on top of
  `CommandEngine` so "prepare a launch" runs a *sequence* (files → timeline →
  slides → schedule) instead of one tool call.

### P1 — Gesture & vision (first NRI-native modality)
- Add **MediaPipe Tasks Vision** (`pose`, `hands`, `gesture_recognizer`) for
  hand-skeleton + gesture classification. Delivered via the existing
  `mediapipe` dependency already used for `LlmInference`.
- Build a `gesture/GestureEngine.kt` package: raw hand landmarks → gesture
  events → `CommandEngine` actions. Ship a small set: swipe, pinch, open/close
  palm, numeric/one-finger tracks.
- **AirScript lite:** sketch a path (finger trajectory) → hash the shape →
  map to a user-trainable command (store in Settings/`Memory`). This is the
  "Gesture Creation Studio" MVP.
- **Fallback:** keep voice as primary; gesture only when camera permission granted.

### P2 — Spatial reality layer
- Add **ARCore** (`sceneform`-free, `com.google.ar:core`) for camera pose +
  plane detection; render Cesium/glTF overlays in a `SurfaceView`/`OpenGL`.
- Object manipulation: raycast hand ray → hit object → grab/rotate/scale by
  hand distance + wrist angle (the "Spatial Manipulation Engine").
- **HoloControl:** draw air dial/slider/knob as AR HUD driven by hand motion.
- Physics via a small rigid-body model (or `physics` lib) for weight/momentum.

### P3 — Intelligence, security, hardware
- **Gesture biometrics:** classify a movement *signature* (finger acceleration,
  rhythm, angle, timing) as an identity factor; combine with voice+context for
  multi-layer auth.
- **Prediction engine:** rolling behavioral model (recent apps, calendar, time)
  to pre-stage workspaces ("Meeting workspace ready? [Start][Cancel]").
- **Hardware:** define the `Axzora Glass / Ring / Ear / Hub` input protos and
  reuse `nearby/` + `call/WsClient` as the device bridge.

## 3. Tech choices
- Gesture/vision: **MediaPipe Tasks Vision** (Android) — hands, pose, gesture.
- Spatial: **ARCore** (`com.google.ar:core`) + OpenGL/glTF renderer.
- ML runtime: reuse the **MediaPipe GenAI** (`LlmInference`) already in the app
  for on-device reasoning; llama-server via `LOCAL_BRAIN_URL` for bigger models.
- Auth: AndroidX `BiometricPrompt` + an FK-style gesture-score gate.
- All P1+ features are **permission-gated and degrade to voice/button** so the
  launcher never bricks if camera/AR is unavailable.

## 4. Suggested first commit (concrete)
1. `gesture/GestureEngine.kt` + `gesture/MotionVec.kt` — hand-landmark →
   gesture classification (MediaPipe hands), unit-tested.
2. Wire one AirScript-style command: "draw **W** → open WhatsApp" using the
   existing `CommandEngine` launch path.
3. Add a `docs/nri/NOTES.md` linking the vision to these modules.

> Keep the AI core (P0) and the voice/launcher intact as the fallback; add
> vision/spatial as an additive modality, never a replacement.
