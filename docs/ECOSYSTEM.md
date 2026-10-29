# AxzoraOS Ecosystem Architecture

One diagram tying the AI core, the communication layer, the impact/economy layer,
and the server together.

## System view

```
                        ┌─────────────────────────────────────┐
                        │         Mr Happy AI Core           │
                        │  assistant/ (Brain, CommandEngine,  │
                        │  LocalLlm)  ·  ceo/ (Agent, Reasoner)│
                        └──────────────┬──────────────────────┘
                                       │ intent / actions
             ┌─────────────────────────┴──────────────────────────┐
             │                Communication Layer                 │
             │  sms/SmsProtocol  ·  sms/SmsGateway                 │
             │  sms/SmsActionSink · sms/EmergencyRouter            │
             │  sms/SmsReceiver (inbound) · SmsAndroidSender       │
             │  wallet/Rail = {INTERNET, BLUETOOTH, NFC, QR, SMS} │
             └───────┬──────────────────────┬─────────────┬───────┘
                     │                      │             │
            ┌────────┴─────────┐   ┌────────┴────────┐  ┌─┴──────────────┐
            │ Human Impact     │   │ Happy Paisa     │  │ Live / Family  │
            │ Network          │   │ Economy         │  │ (location, DM, │
            │ (missions,       │   │ wallet/ + server│  │ calls, nearby) │
            │  rewards)        │   │ wallet.json     │  │                │
            └──────────────────┘   └─────────────────┘  └────────────────┘
                     │                     │                    │
                     └──────────┬──────────┴──────────┬─────────┘
                                │                     │
               ┌────────────────┴───────────────┐     │
               │        Axzora Server (:8787)    │────┘
               │  server.js — /health /publish   │
               │  /members /leave · ledger · HP  │
               │  · call/room signaling · ludo   │
               │  persist: families.json,        │
               │  wallet.json, state.json        │
               └─────────────────────────────────┘
```

## How a Happy Paisa reward flows over SMS (the gateway use case)
1. **Inbound** — `SmsReceiver` (SMS_RECEIVED) → `SmsGateway().handle(sms)`
   → `SmsProtocol.parse` → `Decision`.
2. **Act** — `SmsActionSink.apply(decision)`:
   - `Decision.BankHp` → `wallet.credit(amount, from, note, Rail.SMS)`.
   - `Decision.Reward` → `AdminTools.mint(missionId, reward)` + impact score.
3. **Settle** — the server holds the authoritative `balances`/`ledger` in
   `wallet.json`; the phone keeps a local `WalletStore` copy; `Rail.SMS` records
   the transport.

## Connecting the rails (Emergency Mode)
`EmergencyRouter` picks the best rail from connectivity:
`Internet → WiFi-mesh → Bluetooth → SMS → QR`. `adviseSwitch` auto-raises/lowers;
`send(conn, body, transmit)` fall-throughs until one rail accepts.

## Pieces already real vs. aspirational
- **Real:** Mr Happy AI core (`assistant/`, `ceo/`), Happy Paisa economy
  (`wallet/`, server ledger + stakes), offline Bluetooth/QR HP transfer,
  live-location + family map, calls/DM, the SMS protocol + gateway + router
  (pure-Kotlin core, tested), `Rail.SMS`.
- **Scaffolded:** inbound SMS receiver, SmsManager transport, manifest wiring
  (build on a device).
- **Roadmap:** NRI (gesture/spatial/AR) — see `docs/NRI-ROADMAP.md`.

## Key files
- AI core: `assistant/`, `ceo/`, `happyreddy/`
- Communication: `sms/` (SmsProtocol, SmsGateway, SmsActionSink,
  EmergencyRouter, SmsReceiver, SmsAndroidSender)
- Economy: `wallet/`, `server/server.js`
- Docs: `COMMUNICATION.md`, `NRI-ROADMAP.md`, `SECURITY-AUDIT.md`
