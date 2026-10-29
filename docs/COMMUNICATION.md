# AxzoraOS Communication Layer — SMS Gateway

This maps the "Mr Happy Universal Device Gateway / SMS Bridge" concept to the
actual code, and records what is buildable vs. already wired.

## What already exists
- **SMS send** — `happyreddy/DeviceActions.java` uses `SmsManager.sendTextMessage(...)`.
- **Happy Paisa economy** — `wallet/` (`WalletStore`, `WalletNet`, `LedgerClient`,
  `OfflineTransfer`, `QrCode`, `Currency`) + server `wallet.json`
  (authoritative `balances`, `ledger`, `stakes` = 6% APR / 30-day lock),
  `AdminTools.mint`.
- **Offline HP transfer** — `NearbyScreen` does
  `wallet.credit(hp, from, note, Rail.BLUETOOTH)`; `Rail` enum is
  `INTERNET, BLUETOOTH, NFC, QR, SMS, SYSTEM` (**SMS added**).
- **Mr Happy assistant/agent** — `assistant/` + `ceo/` + `happyreddy/`
  (Gemini tool calls: `admin_mint`, `admin_overview`, send SMS).

## New — wire protocol + gateway (implemented, unit-tested)
| File | Purpose |
|---|---|
| `app/src/main/java/com/axzora/os/sms/SmsProtocol.kt` | Dependency-free codec. `encodeHp` → `MRHAPPY\|HP\|amt\|to\|from\|note`; `encodeImpact` → `MRHAPPY\|IMPACT\|id\|status\|loc\|reward`; `parse()` → typed message. |
| `app/src/main/java/com/axzora/os/sms/SmsGateway.kt` | Decision logic: a received SMS → `Decision.BankHp` / `Decision.Reward` / `Ignore`, firing `onBankHp` → `WalletStore.credit(..., Rail.SMS)` and `onReward` → `AdminTools.mint`; plus a `CommsChannel` fallback order and a **`send()`** path + `buildTransferSms`/`buildImpactSms`. |
| `app/src/main/java/com/axzora/os/sms/SmsReceiver.kt` | Inbound `BroadcastReceiver` for `SMS_RECEIVED` → `SmsGateway.handle(...)` (framework wiring; needs a real build). |
| `app/src/main/java/com/axzora/os/sms/SmsAndroidSender.kt` | `sendText(context)` → `SmsManager.sendTextMessage`, matching the gateway's injected transport. |
| `app/src/main/java/com/axzora/os/sms/SmsActionSink.kt` | Applies a `Decision` to injected side-effects (`bankHp`→`wallet.credit(..., Rail.SMS)`, `reward`→`AdminTools.mint`); `process(Sms)` end-to-end. |
| `app/src/main/java/com/axzora/os/sms/EmergencyRouter.kt` | **Mr Happy Emergency Mode** — best-rail selection + `adviseSwitch` (auto raise/lower the rail: Internet→WiFi→Bluetooth→SMS→QR). |
| `app/src/test/java/com/axzora/os/sms/*` | 24 unit tests (SmsProtocol 5 + SmsGateway 8 + SmsActionSink 4 + EmergencyRouter 7), all pass. |
| `wallet/WalletStore.kt` | `Rail` enum now includes `SMS`. |
| `AndroidManifest.xml` | Added `RECEIVE_SMS` permission + the `.sms.SmsReceiver` (SMS_RECEIVED) receiver. |

## Not wired yet (needs a real device/build)
1. **Reward minting** — wire `Decision.Reward` → `AdminTools.mint(missionId, reward)` and
   an impact score record (receiver currently logs the decision).
2. **`goAsync()` + repository/scope** in `SmsReceiver` for off-main-thread wallet/mint work.
3. **Identity + fraud** — device/wallet identity, duplicate/geo checks (server side).

## Example end-to-end
```
Volunteer texts:  MRHAPPY|IMPACT|829392|COMPLETE|RIVER-12|150.0
   -> SmsGateway.handle() -> Decision.Reward -> AdminTools.mint(150 HP) -> ledger
Farmer texts rewards:  MRHAPPY|HP|500.0|u-bob|u-alice|Plant 100 trees
   -> SmsGateway.handle() -> Decision.BankHp -> wallet.credit(500, U-alice, ..., Rail.SMS)
```

## Resilience (Emergency Mode)
`SmsGateway.fallbackOrder(online, wifiMesh, bluetooth)` → best-first rail list:
`INTERNET → WIFI_MESH → BLUETOOTH → SMS → QR`. Use `primaryRail(...)` for the
current best channel.
