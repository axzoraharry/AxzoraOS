# Axzora OS

A customizable Android **home-screen launcher** delivered as a single installable APK.
It replaces your phone's home screen and bundles:

- **Home** — clock, greeting, favorites grid, quick assistant bar, app dock
- **App drawer** — search, favorite (★), and hide (👁) any installed app
- **Voice commands** — fully **on-device** speech recognition (no cloud)
- **Chat assistant** — offline, rule-based; type commands in natural language
- **Location** — **opt-in** family sharing on an OpenStreetMap map
- **Customization** — dark/light theme, 6 accent colors, home grid density, display name

> This is a launcher **app**, not a flashed ROM. It installs like any APK and can be set
> as your default Home screen. A true custom OS/ROM is a separate, much larger effort.

## What the assistant understands (on-device)

`open <app>` · `call <number>` · `set alarm 7:30 am` · `set timer 5 min` ·
`flashlight on/off` · `what's the time / date / battery` · `search <query>` ·
`navigate to <place>` · `where am I` · `help`

## Build

Requirements: JDK 17+, Android SDK (platform 34, build-tools 34). `local.properties`
already points at the SDK on this machine.

```bash
# from the project root
./gradlew assembleDebug
```

The APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Install on a phone

1. Enable **Developer options → USB debugging** on the phone and plug it in.
2. Then:

```bash
./gradlew installDebug
```

Or copy `app-debug.apk` to the phone and open it (allow "install from this source").

To use it as your home screen: press Home once, or **Settings → Apps → Default apps →
Home app → Axzora OS**.

## Privacy & location — read this

Location sharing is **consent-based by design**:

- Nobody is tracked unless they **install this app**, enter the **same family code**
  (Settings), and turn the **Share my location** switch **on**.
- While sharing is on, a persistent notification shows it's active.
- Do **not** use this to track anyone without their knowledge — that is illegal in
  most places. The app is built to make covert tracking impossible.

## Cross-device location sharing — run the laptop as the server

The repo includes a zero-dependency Node server (`server/server.js`) that acts as the
sync backend. Your laptop hosts it; phones talk to it over your network.

### 1. Open the firewall (once)

In an **elevated** PowerShell (Run as administrator):

```
powershell -ExecutionPolicy Bypass -File .\server\allow-firewall.ps1
```

It opens TCP **8787** on **Private** networks only, then prints the URLs to use.

### 2. Start the server

Double-click `server/start-server.bat`, or:

```
node server/server.js
```

It prints every usable address, e.g. `http://192.168.1.20:8787`. Leave it running.

### 3. Point each phone at it

On every phone: **Settings → Server URL** → paste the URL → **Test connection**
(should say "Connected"). Then set the **same Family code** on all phones and turn
**Share my location** on.

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | liveness + group count |
| `POST` | `/publish` | `{code, deviceId, name, lat, lon}` |
| `GET` | `/members?code=X` | everyone sharing in that group |
| `POST` | `/leave` | `{code, deviceId}` — remove this device |

State lives in `server/families.json`. Devices silent for 24h are dropped
automatically, and turning sharing off calls `/leave` immediately.

### Limits of the laptop-as-server setup

- **Same network only.** Phones must be on the same Wi-Fi/hotspot as the laptop.
  Away from home, sharing stops until they're back.
- **Laptop must be awake** with the server window open. Sleep = no sync.
- **The LAN IP changes** between networks, so the Server URL may need updating.
- **Plain HTTP on your LAN.** Fine at home; do not port-forward this to the
  internet as-is.

### Making it work anywhere (optional)

Tailscale is already installed on this laptop. Once you sign in to it and install
Tailscale on the phones, the laptop gets a stable `100.x.x.x` address that works on
any network — put `http://100.x.x.x:8787` in Server URL and the same-Wi-Fi limit
disappears. Nothing in the app needs rebuilding.

Swapping in a hosted backend instead: `HttpFamilySync` and the `FamilySync` interface
(`app/src/main/java/com/axzora/os/location/FamilySync.kt`) are the only seam — point
`Graph.kt` at a different implementation and everything else is unchanged.
