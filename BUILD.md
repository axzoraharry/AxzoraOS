# Building Axzora OS

Everything needed to compile the app and run the backend. No Android Studio
required — the Gradle wrapper handles its own toolchain.

---

## 1. Prerequisites

| Need | Version | Notes |
|---|---|---|
| **JDK** | 17 or newer | `java -version` |
| **Android SDK** | platform 34, build-tools 34 | Android Studio, or command-line tools |
| **Node.js** | 18+ | only for the backend |

The Gradle wrapper (`gradlew`) downloads Gradle itself on first run, so nothing
else is needed.

### Point Gradle at your SDK

Create `local.properties` in this folder — it is machine-specific and
deliberately not included.

**Windows — backslashes must be doubled.** This is a Java properties file, where
a single `\` is an escape character. Getting this wrong produces the unhelpful
error *"The filename, directory name, or volume label syntax is incorrect"*:

```
sdk.dir=C\:\\Users\\YOURNAME\\AppData\\Local\\Android\\Sdk
```

Forward slashes also work and avoid the problem entirely:

```
sdk.dir=C:/Users/YOURNAME/AppData/Local/Android/Sdk
```

macOS/Linux:

```
sdk.dir=/Users/YOURNAME/Library/Android/sdk
```

Alternatively set the `ANDROID_HOME` environment variable and skip the file.

---

## 2. Build the APK

Windows:

```bash
cd AxzoraOS && .\gradlew.bat :app:assembleDebug
```

macOS/Linux:

```bash
cd AxzoraOS && ./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (~60 MB — WebRTC ships
native libraries for every CPU architecture).

Install to a connected phone:

```bash
cd AxzoraOS && ./gradlew :app:installDebug
```

The separate Axa Store app builds with `:store:assembleDebug`.

### Smaller APKs (optional)

Roughly 40 MB of the APK is WebRTC binaries for architectures a given phone
will never use. Splitting per-ABI cuts it to ~20 MB each — add to
`app/build.gradle.kts` inside `android { }`:

```kotlin
splits {
    abi {
        isEnable = true
        reset()
        include("armeabi-v7a", "arm64-v8a")
        isUniversalApk = false
    }
}
```

---

## 3. Run the backend

The app needs a server for anything between devices: family map, private chat,
Happy Paisa transfers, and call setup. Nearby (Bluetooth / Wi-Fi Direct) works
with no server at all.

```bash
cd AxzoraOS/server && node server.js
```

It listens on port **8787** and prints every address it can be reached on.
There are no npm dependencies — it uses only Node's standard library.

### Point the app at it

Edit `app/src/main/java/com/axzora/os/Config.kt`:

```kotlin
val SERVER_CANDIDATES = listOf(
    "https://your-server.example.com",   // tried first
    "http://192.168.1.20:8787",          // laptop on the same Wi-Fi
)
```

The app probes these **concurrently** at startup and keeps whichever answers,
remembering it for next time. Adding an address means existing installs keep
working when the backend moves — no reinstall needed.

`server/DEPLOY.md` covers putting the server on a permanent host.

---

## 4. Project layout

```
AxzoraOS/
├── app/                     the launcher itself
│   └── src/main/java/com/axzora/os/
│       ├── MainActivity.kt      home screen, navigation, wiring
│       ├── Config.kt            server list, admin code, HP rate
│       ├── assistant/           offline voice + chat command engine
│       ├── call/                WebRTC voice & video, background service
│       ├── chat/                end-to-end encrypted family messaging
│       ├── launcher/            installed-app listing
│       ├── location/            family map sharing
│       ├── nearby/              offline peer-to-peer over radio
│       ├── net/                 server discovery
│       ├── wallet/              Happy Paisa, ledger, offers, QR
│       └── ui/                  theme and visual effects
├── store/                   Axa Store — installs .axo packages
└── server/                  Node backend (zero dependencies)
    ├── server.js
    ├── pack-axo.js          turns an APK into an .axo package
    └── DEPLOY.md
```

---

## 5. Things worth knowing before changing code

- **Server discovery** — `net/ServerFinder.kt` probes all candidates at once
  rather than in sequence. A dead address on mobile data can take 10+ seconds to
  time out, and probing serially stacks those delays.

- **Calls need the foreground service.** `call/CallService.kt` is what makes a
  phone reachable while the app is closed; Android freezes cached processes, so
  polling from an Activity stops the moment the app is backgrounded.

- **Video is always negotiated**, even for audio calls. WebRTC fixes its media
  sections when the session is created, so a call that starts without a video
  track can never receive the other side's camera.

- **The ledger is authoritative.** Phones keep a local balance for speed and
  offline use, but `server/ledger.json` is the record that settles disputes.
  Offline vouchers carry a nonce the ledger refuses to accept twice.

- **Family codes are normalised** to uppercase on both sides. They were once
  case-sensitive, which silently split one family into two groups.

- **Happy Paisa is in-app points**, not currency. `Config.HP_TO_INR` and
  `wallet/Currency.kt` control how a balance is displayed in local money.

---

## 6. Admin

Minting is authorised **by the server**, not the app — an admin code inside an
APK can be read by anyone who unpacks it. Set your own:

```bash
cd AxzoraOS/server && AXZORA_ADMIN_CODE=your-secret node server.js
```

Windows PowerShell:

```powershell
$env:AXZORA_ADMIN_CODE='your-secret'; node server.js
```

---

## 7. Known limits

- **No TURN server.** Calls use public STUN, which works on most networks. Two
  strict symmetric NATs leave no direct path and the call fails with a message
  rather than silence. A TURN relay (self-hosted coturn, or a paid service)
  fixes it.
- **Debug signing.** Fine for sideloading; Play Store needs a release key.
- **Aggressive OEMs** (Xiaomi, Oppo, Vivo, Realme) may kill the call service.
  Firebase push is the proper fix and needs a Firebase project.
- **Offline double-spend** is bounded, not eliminated: a voucher can be spent
  twice while both phones are offline, and is reversed once either reconnects.
