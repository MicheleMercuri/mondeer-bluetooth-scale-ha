# WeighAi — Android companion app

Native Android app that displays the readings of the Mondeer/WanKa C1
Bluetooth scale received via MQTT from Home Assistant. Exports to
**Health Connect** so Samsung Health, Google Fit, Fitbit and any other
compatible app pick up the data automatically.

## Build environment

1. **Android Studio Ladybug | 2024.2.1+** (or newer)
2. Open the project: `File → Open` → select the `android-app/` folder
3. Android Studio will fetch the dependencies and the Gradle wrapper
4. Wait for the Gradle sync banner ("Sync Now") to finish

## Build & install (sideload)

Connect an Android device with **USB debugging enabled**:

1. Open the device in Device Manager (Tools → Device Manager)
2. Run → Run 'app' (Shift+F10) → pick the device → build & install
3. The `WeighAi` app appears in the launcher

To produce a shareable APK without Android Studio:

```bash
./gradlew assembleDebug
# Output APK: app/build/outputs/apk/debug/app-debug.apk
```

You can then send the APK via Telegram / email / Drive — the recipient
must enable "Install unknown apps" for that source and tap to install.

## First-run setup wizard

On first launch the app prompts for:

- MQTT broker host (FQDN or IP)
- MQTT broker port
- MQTT username + password
- **Which family member is the phone owner** (the user picks one of the
  profiles configured on the listener side)

Credentials are stored via `EncryptedSharedPreferences` (Android
Keystore-backed), never in plaintext.

## Privacy by design — one phone, one user

The app is **single-user**: each phone shows **only its owner's
readings**, never the other family members'. The profile selected in
the setup wizard determines the only MQTT topic the app subscribes to:

| Phone owner   | Configured profile | Subscribed MQTT topic                        |
|---------------|--------------------|----------------------------------------------|
| Father        | `father`           | `bilancia_mondeer/peso_father/state`         |
| Mother        | `mother`           | `bilancia_mondeer/peso_mother/state`         |
| Son           | `son`              | `bilancia_mondeer/peso_son/state`            |

The same applies to Health Connect: each phone writes only its owner's
readings to the local HC store. There is no "family overview" inside
the app — the multi-user dashboard lives on Home Assistant.

## Tech stack

- **Kotlin 2.2** + **Jetpack Compose** (declarative UI)
- **Material 3** + dynamic color (Material You) on Android 12+
- **Hilt** for dependency injection
- **Room** (SQLite) for offline storage
- **HiveMQ MQTT Client** (modern replacement for the deprecated
  `paho-mqtt-android`)
- **Health Connect SDK 1.1.0 stable** — single hub that propagates
  to Samsung Health, Google Fit, Fitbit, Garmin Connect, etc.
- **Coroutines + Flow** for reactive data flows
- Custom Compose Canvas charts — no external chart library

## A note on UI strings

The UI is written in **Italian** (the original audience). Strings are
inlined in Compose calls (`Text("…")`), not in `res/values/strings.xml`.
A future pass will move them to a localized `strings.xml` so other
locales can override them.

## Health Connect integration

Since 2023 Samsung **deprecated** its proprietary SDK (Samsung Health
Data SDK) and pointed all developers to **Health Connect**, Android's
unified health hub. Google Fit (being phased out) and Fitbit followed
the same path. A single integration on the app side automatically
covers all those ecosystems:

```text
WeighAi (this app)
     │
     │ Health Connect SDK
     ▼
┌──────────────────┐
│  Health Connect  │ (preinstalled on Android 14+)
└────────┬─────────┘
         │ (auto-sync once the user grants consent)
         ├──▶ Samsung Health
         ├──▶ Google Fit
         ├──▶ Fitbit
         ├──▶ Garmin Connect
         └──▶ any other compatible app
```

### Enabling Samsung Health to read the data

1. Health Connect (preinstalled on Android 14+, otherwise from Play Store)
2. Open Samsung Health → Settings → **Health Connect** → toggle ON
3. Open this app → Setup → **Export to Health Connect** → grant the
   permissions for `Body Weight`, `Body Fat`, `Hydration`,
   `Bone Mass`, `Lean Body Mass`, `Basal Metabolic Rate`

From that point on every reading is written to Health Connect and
Samsung Health receives it within seconds.

### Exported metrics

| App field         | Health Connect record type                            |
|-------------------|-------------------------------------------------------|
| `weight_kg`       | `WeightRecord`                                        |
| `fat_pct`         | `BodyFatRecord`                                       |
| `water_pct`       | `BodyWaterMassRecord` (computed: weight × pct/100)    |
| `bone_kg`         | `BoneMassRecord`                                      |
| `muscle_kg`       | `LeanBodyMassRecord` (computed: weight × (1 − fat%))  |
| `bmi`             | derived in HC from WeightRecord + height in profile   |
| `calorie_kcal`    | `BasalMetabolicRateRecord`                            |

## Project structure

```text
app/src/main/
├── AndroidManifest.xml
├── kotlin/it/mercuri/bilancia/
│   ├── BilanciaApp.kt              Application + Hilt entry point
│   ├── MainActivity.kt             Single activity + nav graph
│   ├── data/
│   │   ├── db/                     Room (entity, dao, database)
│   │   ├── mqtt/                   HiveMQ client wrapper
│   │   ├── healthconnect/          Health Connect exporter
│   │   ├── log/                    in-app debug log buffer
│   │   ├── repository/             single source of truth
│   │   └── AppPrefs.kt             EncryptedSharedPreferences wrapper
│   ├── domain/                     models + use cases
│   ├── ui/
│   │   ├── theme/                  Material 3 colors
│   │   ├── home/                   main screen + Hero3DGauge
│   │   ├── setup/                  first-run wizard
│   │   └── common/                 reusable Compose components
│   └── res/
│       ├── values/                 strings, themes, colors
│       └── values-night/           dark theme overrides
```

## Minimum SDK

- **Android 14 (API 34)** — required for native Health Connect on
  Android 14+. Older versions can install Health Connect via the Play
  Store but are not the primary target.

## License

MIT — see the LICENSE file in the repo root.
