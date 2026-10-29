# Mr Happy — Car-Aware, Always-On Assistant (decisions + plan)

The ask: say **"Mr Happy"** → the assistant starts talking in the current launcher;
keep listening and follow rules; connect to the **Mercedes car** to show the way
(including nearby ambulances); wherever the car goes, broadcast to everyone
nearby; and keep it maintained with the **digital CEO**.

This notes the decisions made and maps each to real AxzoraOS code.

## Decisions & mapping

| Requirement | Decision | Existing hook |
|---|---|---|
| "Say Mr Happy → talks in launcher" | Wake-word (Porcupine) triggers `openMrHappy(ctx, wake=true)` which opens the assistant bubble/overlay in the launcher. | `MainActivity.openMrHappy(context, wake)` (the `wake` extra), `assistant/FloatingService`/`FloatingUi` |
| "Keep listening & follow the rules" | An always-on wake-word service + a **RulesEngine** (below). Rules are data, checked each turn. | `assistant/VoiceController` (ASR) + Porcupine `.so`; JNI `libpv_porcupine` |
| "Connect with Mercedes car" | Tier 1 (now): use the device's own GPS as the car. Tier 2 (needs creds): **Mercedes-Benz Connected Vehicle API** (Mercedes me OAuth) for real vehicle location/status. | `location/LocationRepository`; a new `car/` client |
| "Show the way / ambulance" | `CarAwareness` computes the nearest hospital/ambulance/fire and emergency signals; navigation is launched via a geo nav intent. | `car/CarAwareness` (new, tested) |
| "Wherever the car goes, send to all nearby" | Publish the car as a `vehicle` to the family/live map (`/publish`), which is already rendered for nearby users. | `Graph.familySync.publish` + `server.js` `/publish` (`kind=vehicle,heading,speed`) |
| "Present & maintain with digital CEO" | `CeoAgent.run(request, worldName)` plans and routes; `WorldEngine` maintains the world/dashboard state. | `ceo/CeoAgent`, `ceo/WorldEngine`, `ceo/WorldStore` |

## Implemented (pure, testable) — `com.axzora.os.car.CarAwareness`
- `Pose(lat, lon, speedKmh, headingDeg)`.
- `nearestHospital` / `nearestAmbulance` / `nearest(car, facilities, kind)` (haversine).
- `emergencyNearby(car, facilities, radiusM)` (hospital/ambulance/fire).
- `publishPayload(car, familyCode, deviceId, name)` → matches `/publish` vehicle fields.
- `routeSummary(car, dest)`.

Tests: `car/CarAwarenessTest` — **7/7 pass**.

## Needs hardware / credentials (not buildable in-unit-sandbox)
- **Always-on mic + wake-word service** on a real device (Porcupine runtime + mic permission).
- **Mercedes me API**: client id/secret + vehicle VIN (OAuth) — for real car data.
- **GPS** live (from the device/car).
- **POI/facilities data** (hospitals/ambulance stations) — e.g. Overpass/OSM query or a curated list.
- A **navigation app** (Google Maps/geo intent) to "show the way".

## Next buildable pieces (on a real build)
1. `assistant/WakeWordService` — Porcupine hotword → `openMrHappy(wake=true)`.
2. `car/MercedesClient` — Mercedes me OAuth client (needs creds).
3. A **RulesEngine** (`rules/`) evaluating: wake → respond; car GPS → publish; emergency → alert + nav; CEO report.
4. Wire the car vehicle publish into the family/live map so "nearby" devices see it.

## Notes
- Everything in `cp/` logic is pure/interface-driven so it's testable without
  Android, matching the Communication Layer (`sms/`) approach.
- The Mercedes/ambulance data and the always-on mic are the real-device parts;
  the **decision logic is done and tested**.
