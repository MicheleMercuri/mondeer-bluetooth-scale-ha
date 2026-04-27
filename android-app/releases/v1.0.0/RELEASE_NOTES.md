# WeighAi 1.0.0 — 2026-04-28

First stable release of the companion app for the Mondeer/WanKa C1
smart scale + Python listener on Home Assistant.

## Components

- **Python listener** (`listener/scale_listener.py`) — runs on a
  Windows mini-pc; receives BLE advertising from the scale, runs the
  handshake (binding match, time sync, family profiles) and publishes
  the readings via MQTT to HA.
- **HA package** (`home_assistant/packages/bilancia.yaml`) — exposes
  `peso_<profile>` sensors, drives the Telegram inquiry automation
  and the FCM push to the WeighAi phone.
- **Android app** (`android-app/`) — futuristic UI, Health Connect
  integration, live MQTT.

## Highlights

### Listener / BLE protocol

- Full reverse-engineering of the handshake: device info → binding
  match (`cmd=2 data=104`, `type=0/subtype=1`) → 104 ACK → time sync
  (`data=103`) → wait for `data=3` (settings) → send profiles
  (`data=102`).
- `o()` recovery (mimicks `c.java::a(h hVar)` in the OEM Android
  app): if a weight frame arrives with `user_id=0, age=0,
  owner_type=1` the listener resends the family profiles. The next
  reading carries the full BIA — no extra step on the user's side.
- Pre-pairing on the 3rd connect attempt — works around a flaky
  Realtek BLE chipset.
- Idle timeout 30s → 90s — BIA can take 10–20 s after the first
  weight frame on cool/dry feet.
- Async `_mqtt_health_loop` every 30 s — paho-mqtt does not auto
  re-subscribe after an internal reconnect; the loop keeps the
  client (and its inquiry subscription) alive.
- `on_connect` re-subscribe — same reason.
- Triple retry on publish timeout post-Telegram-confirm.
- Telegram-inquiry deduplication within 60 s — the scale sends 2–3
  complete frames per session; without dedup each would generate a
  Telegram prompt and a duplicated push notification.

### Android app — UI

- **Hero3DGauge** for the weight: 270° arc with sweep gradient, neon
  blur backlight that pulses in the status colour, animated marker
  with a halo, count-up number animation.
- **MetricRingCard** for fat / water / muscle / bone / visceral —
  each metric has its own mini ring-gauge with backlight blur, the
  optimal range is highlighted, the marker animates to the value.
- **MeshBackground** — three radial blobs animated independently
  (9s / 13s / 17s periods) in the colour of the current weight
  status.
- **Trend chart** that reactively recomputes points and scale when
  the user switches metric (Weight → BMI → Fat → Water → Muscle →
  Bone → Visceral). Records without that field (e.g. preliminaries)
  are filtered out automatically.
- Status labels are now **Low / Optimal / High** (changed from
  *under / optimal / over*).
- `onResume` triggers an MQTT refresh — HiveMQ tends to lose the
  connection during Doze and the app no longer needs an uninstall to
  recover.
- Deep link `weighai://main` handles HA push-notification taps.
- Password "show/hide" toggle in the setup wizard.
- Local-time parsing fixed (was UTC, off by the local TZ offset).

### Health Connect integration

- Toggle "Export to Health Connect" in the setup wizard.
- Permission request via
  `PermissionController.createRequestPermissionResultContract()`.
- Manifest declares `<uses-permission>` for the 6 record types and
  the `<intent-filter>` for `VIEW_PERMISSION_USAGE` +
  `HEALTH_PERMISSIONS` — required for the integrated HC on Android
  14+ to even discover the app.
- "Test write" button — writes a fixed 70 kg sample to verify the
  pipeline.
- "Sync last 30 days" — backfills Room → HC.
- "Debug log" panel inside the app, with copy/clear actions —
  removes the need for `adb logcat` for HC troubleshooting.

### Known limitation

- **HA history is not imported**: the HC backfill only reads from
  Room, which only contains readings received via MQTT after the app
  was installed. Readings recorded before the app install stay on
  HA. The next release will add a REST import from
  `/api/history/period/...` to populate Room.

### Stack

- Listener: Python 3.12 + bleak + paho-mqtt
- App: Kotlin 2.2.10 + Compose + Hilt 2.56 + Room 2.7.2 + HiveMQ MQTT
  1.3.7 + Health Connect 1.1.0 stable
- Build: AGP 9.1.1 + Gradle 9.3.1, JDK 21 (Android Studio JBR),
  `targetSdk = 36`
