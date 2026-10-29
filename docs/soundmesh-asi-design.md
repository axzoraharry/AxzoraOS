# SoundMesh ASI — Architecture Design (honest scope)

> This is a **design document**, not a claim that the whole thing is built.
> The goal is an internet-independent, AI-assisted device-to-device acoustic
> communication layer (speaker → microphone), eventually with Wi-Fi Direct /
> Bluetooth / optical fallbacks and on-device reasoning.

## Honest feasibility split

The PRDs (v1/v2, Phase 1–4) mix **provably buildable engineering** with **genuine
research & development**. I will be honest about which is which.

### ✅ Buildable now (pure DSP / crypto / protocol — no ML training or hardware)
- Acoustic modem: 2-FSK tones + **Goertzel** decoder (no FFT lib).
- **Bit-repetition FEC** (3× + majority vote) + **CRC32** integrity.
- **AES-256** encrypted payload.
- **Adaptive bitrate** (choose frequency spacing / symbol time / repeat factor
  based on a measured noise floor — a threshold, *not* a neural net).
- Packet framing: `[Preamble][Length Header][Payload][FEC][CRC]`.
- **This is what the SoundMesh codec in the app does today.**

### ⚠️ Real R&D / ML (NOT buildable as a working feature in one pass)
These need trained models, data, and/or substantial engineering:
- **Neural audio decoder / noise classifier / semantic error correction**
  (need a dataset + an ONNX/TFLite model; e.g. a small CNN/DSPnet for tone
  classification under noise — this is trainable but is a real ML project).
- **Reed-Solomon / LDPC** error correction (needs GF(2^8) arithmetic + codec lib;
  Reed-Solomon is implementable, LDPC is heavy).
- **Whisper-style speech** + **on-device Tiny LLM** (model files + runtime; the
  app already has some on-device AI, but a full STT/LLM is its own large task).
- **Mesh routing / Wi-Fi Direct / Bluetooth / optical handshake** (different
  transports, discovery, routing — a networking project).
- **Camera vision / environment detection**, **AI anomaly detection**,
  **device learning profiles** (need sensors + models + work).

---

## Proposed architecture

```
soundmesh/
├── SoundMesh.kt                 # ✅ codec: FSK + Goertzel + FEC(3x) + CRC + AES  (DONE)
├── SoundMeshScreen.kt           # ✅ UI: send(speaker) / listen(mic)  (DONE)
├── codec/
│   ├── Modulator.kt             # bits -> PCM tones (2-FSK, optional OFDM later)
│   ├── Demodulator.kt           # PCM -> bits  (Goertzel, later neural/adaptive)
│   └── Sync.kt                  # preamble/lock + symbol-clocking
├── fec/
│   ├── Repetition.kt            # ✅ 3x repeat + majority vote      (DONE)
│   └── ReedSolomon.kt           # (future) RS(255,223) + interleaving
├── security/
│   ├── AesGcm.kt                # ✅ AES-256-CBC (demo key)
│   ├── KeyExchange.kt           # (future) ECDH/Curve25519 per-session
│   └── Identity.kt              # (future) device certificates
├── adaptive/
│   ├── NoiseFloor.kt            # ✅ measure RMS/band energy to pick params
│   └── BitrateController.kt     # choose repeat factor / symbol time (auto-adapt)
├── brain/                       # (future: Phase 2+) on-device ML
│   ├── AudioFrontend.kt         # windowing, log-mel
│   ├── NoiseNet.kt              # ONNX/TFLite noise remover + tone classifier
│   └── Intent.kt                # tiny intent parsing of short messages
├── mesh/                        # (future: Phase 3) multi-transport
│   ├── Discovery.kt             # beacon + RSSI-based ranking
│   └── Router.kt                # A->B->C relay, path selection
└── packet/
    └── SmapPacket.kt            # Preamble|DeviceID|EnvProfile|EncHdr|Payload|FEC|CRC|Signature
```

## Protocol — SMAP packet (target)

```
[Preamble (lock)]  [Device ID]  [Env Profile byte]
[Encr. Header]     [Payload]    [FEC parity]  [CRC32]  [AI Signature]
```

## Implementation stack
- **App**: Kotlin (already using AudioTrack/AudioRecord, javax.crypto, java.util.zip.CRC32).
- **DSP**: pure-compile Goertzel → later FFT via `android.media`/`kotlin.math` or jTransforms.
- **AI**: `org.tensorflow:tensorflow-lite` or `ai.onnxruntime:onnxruntime-android` for ONNX/TFLite models.
- **Security**: `javax.crypto` now; `org.bouncycastle` / `Tink` for ECDH/Curve25519 later.
- **No server**: fully P2P, on-device.

## Status of what's actually implemented
- `SoundMesh.kt`: 2-FSK + Goertzel + 3× FEC + CRC32 + AES-256. Verified round-trip:
  `hi`, `Hello`, `Meet at 5pm` all decode; 1 corrupted window is recovered.
- `SoundMeshScreen.kt`: send (speaker) / listen (mic) UI.
- These are the honest **Phase 1** "stable acoustic messaging + better FEC"
  foundation. The AI/neural/mesh layers are **designed but not built** and need
  real ML/network work.
