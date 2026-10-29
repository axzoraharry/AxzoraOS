# Third-Party Notices — Axzora OS

Axzora OS bundles and links third-party software. This file lists them and their
licenses. Full license texts are available at the linked repositories, or in the
per-component LICENSE/NOTICE files shipped alongside the app.

## Gradle / Maven dependencies

| Component | License |
|---|---|
| androidx.core:core-ktx, androidx.lifecycle (runtime-ktx, runtime-compose), androidx.activity:activity-compose, androidx.navigation:navigation-compose, androidx.datastore:datastore-preferences | Apache License 2.0 |
| Jetpack Compose (ui, ui-graphics, ui-tooling-preview, material3, material-icons-extended) | Apache License 2.0 |
| Google Play Services (location, nearby) | Apache License 2.0 (Google terms apply) |
| Google MediaPipe `tasks-genai` | Apache License 2.0 |
| io.getstream:stream-webrtc-android (WebRTC) | BSD 3-Clause |
| org.osmdroid:osmdroid-android | Apache License 2.0 |

## Bundled native libraries / JARs (`app/libs`, `jniLibs`)

| Component | License | Notes |
|---|---|---|
| **Vosk** (`libvosk.so` + the speech model in `assets/model`) | Apache License 2.0 | Model © **Alpha Cephei Inc, 2020** (see `assets/model/README`). Full Apache-2.0 text in `assets/model/LICENSE`. |
| **Picovoice Porcupine** (`libpv_porcupine.so`, `porcupine-core.jar`) | Picovoice (proprietary / free-non-commercial, requires a Picovoice access key) | See https://picovoice.ai/license/ |
| **JNA** (`libjnidispatch.so`, `jna.jar`) | LGPL 2.1 / Apache 2.0 (dual) | |

## Bundled source

| Component | License |
|---|---|
| `java/okhttp3/*` — minimal Apache-2.0 okhttp-compatible stubs used only by Porcupine's unused cloud-train path | Apache License 2.0 |

## Bundled web assets (`assets/`)

| Component | License |
|---|---|
| `ludoclassic/` (Ludo Classic) | MIT — see `assets/ludoclassic/LICENSE` |
| `xr/`, `games.html`, `ludo.html`, `ludo3d.html`, `hud.html`, `hud` | See source projects; attribution noted in each file's header where applicable |

## Notes
- The app does not ship any API keys. `assets/keys.json` is intentionally empty and gitignored.
- Vosk model attribution: "US English model for mobile Vosk applications, Copyright 2020 Alpha Cephei Inc."
