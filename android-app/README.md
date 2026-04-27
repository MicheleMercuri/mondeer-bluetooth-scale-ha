# Bilancia — App Android

App Android nativa per visualizzare le pesate della bilancia Mondeer/WanKa C1
ricevute via MQTT da Home Assistant. Esporta verso Health Connect → Samsung
Health, Google Fit, Fitbit, ecc.

## Setup ambiente

1. **Android Studio Ladybug | 2024.2.1+** installato
2. Apri questo progetto: `File → Open` → seleziona la cartella `android-app/`
3. Android Studio scarica automaticamente le dipendenze e genera il Gradle wrapper
4. Sync gradle (banner giallo "Sync Now"): aspetta che finisca

## Build & install (sideload)

Dispositivo Android collegato via USB con **debug USB attivo**:

1. Apri il dispositivo in Device Manager (Android Studio → Tools → Device Manager)
2. Run → Run 'app' (Shift+F10) → seleziona dispositivo → Build & install
3. L'app `Bilancia` appare nel drawer

In alternativa per condividere l'APK fuori da Android Studio:

```bash
./gradlew assembleDebug
# APK output: app/build/outputs/apk/debug/app-debug.apk
```

Poi mandalo via Telegram/email ai familiari, abilitano "Origini sconosciute"
e installano.

## Configurazione iniziale

Al primo avvio l'app chiede:

- Indirizzo broker MQTT (es. `192.168.1.190`)
- Porta (default 1883)
- Username + password MQTT
- **Quale utente sei tu** (Michele/Maria Luisa/Matilde)

Le credenziali vanno in EncryptedSharedPreferences (Android Keystore-backed).

## Privacy by design — un telefono = un utente

L'app è **single-user**: ogni telefono mostra **solo le pesate del proprio
proprietario**, mai quelle degli altri familiari. La scelta del profilo
fatta nel setup wizard determina l'unico topic MQTT a cui l'app fa
subscribe:

| Telefono                | Profilo configurato | Topic MQTT (solo questo)                    |
|-------------------------|---------------------|---------------------------------------------|
| S24 di Michele          | `michele`           | `bilancia_mondeer/peso_michele/state`       |
| Telefono di Maria Luisa | `maria_luisa`       | `bilancia_mondeer/peso_maria_luisa/state`   |
| iPhone di Matilde       | `matilde`           | `bilancia_mondeer/peso_matilde/state`       |

Stessa logica per Health Connect: ogni telefono scrive sull'HC del proprio
proprietario solo le proprie pesate. Niente "vista famiglia" sull'app —
l'overview multi-utente resta su Home Assistant (dashboard "Biometria").

## Stack tecnico

- **Kotlin 2.1** + **Jetpack Compose** (UI declarativa)
- **Material 3** + dynamic color (Material You) su Android 12+
- **Hilt** per dependency injection
- **Room** (SQLite) per storage offline
- **HiveMQ MQTT Client** (sostituto moderno di paho-mqtt-android)
- **Vico** per i grafici
- **Health Connect SDK** (hub unico → propaga a Samsung Health, Google
  Fit, Fitbit, Garmin Connect, ecc. — vedi sezione dedicata sotto)
- **Coroutines + Flow** per reattività

## Integrazione Samsung Health / Google Fit / Fitbit

Dal 2023 Samsung ha **deprecato** la propria SDK proprietaria
(Samsung Health Data SDK) e ha indirizzato tutti gli sviluppatori su
**Health Connect**, l'hub unificato Android di Google. Stessa cosa
hanno fatto Google Fit (in fase di chiusura) e Fitbit. Una singola
integrazione lato app copre quindi automaticamente tutti questi
ecosistemi:

```text
Bilancia (questa app)
     │
     │ Health Connect SDK
     ▼
┌──────────────────┐
│  Health Connect  │ (preinstallato su Android 14+)
└────────┬─────────┘
         │ (sync automatico, una volta dato il consenso)
         ├──▶ Samsung Health
         ├──▶ Google Fit
         ├──▶ Fitbit
         ├──▶ Garmin Connect
         └──▶ qualunque altra app compatibile
```

### Setup utente per vedere i dati in Samsung Health

1. Health Connect (preinstallato su Android 14+, altrimenti Play Store)
2. Aprire Samsung Health → Settings → **Health Connect** → toggle ON
3. Aprire questa app → Impostazioni → **Esporta su Health Connect** →
   concedere i permessi richiesti per le metriche `Body Weight`,
   `Body Fat`, `Hydration` (= acqua corporea), `Bone Mass`,
   `Lean Body Mass` (= massa muscolare), `Basal Metabolic Rate` (= BMR)

Da quel momento ogni pesata viene scritta su Health Connect e
Samsung Health la riceve entro pochi secondi.

### Metriche esportate

| Metrica nostro modello | Health Connect record type                            |
|------------------------|-------------------------------------------------------|
| `weight_kg`            | `WeightRecord`                                        |
| `fat_pct`              | `BodyFatRecord`                                       |
| `water_pct`            | `HydrationRecord` (calcolato)                         |
| `bone_kg`              | `BoneMassRecord`                                      |
| `muscle_kg`            | `LeanBodyMassRecord`                                  |
| `bmi`                  | calcolato da WeightRecord + altezza nel profilo HC    |
| `calorie_kcal`         | `BasalMetabolicRateRecord`                            |

## Struttura progetto

```text
app/src/main/
├── AndroidManifest.xml
├── kotlin/it/mercuri/bilancia/
│   ├── BilanciaApp.kt              Application + Hilt entry
│   ├── MainActivity.kt             Single activity + nav graph
│   ├── data/
│   │   ├── db/                     Room (entity, dao, db)
│   │   ├── mqtt/                   HiveMQ client wrapper
│   │   └── repository/             BilanciaRepository
│   ├── domain/                     modelli + use case
│   ├── ui/
│   │   ├── theme/                  Material 3 colors
│   │   ├── home/                   schermata principale (3 cards user)
│   │   ├── detail/                 dettaglio utente con grafici
│   │   ├── setup/                  wizard configurazione iniziale
│   │   └── common/                 componenti riusabili
│   ├── healthconnect/              integrazione Health Connect
│   └── notify/                     notifiche push locali
└── res/
    ├── values/                     strings, themes, colors
    └── values-night/               theme dark
```

## Min SDK

- **Android 14 (API 34)** — necessario per Health Connect nativo.
  Tutti i telefoni di casa (Samsung S24, ecc.) sono Android 16+, ampiamente coperti.

## Roadmap

- [x] Scaffold del progetto + theme + Home con cards mock
- [ ] Setup wizard MQTT + persistenza credenziali cifrate
- [ ] HiveMQ MQTT client + subscribe ai topic `bilancia_mondeer/peso_*/state`
- [ ] Room database con history pesate
- [ ] Detail screen con grafici Vico (peso/grasso/acqua nel tempo)
- [ ] Notifiche push al ricevimento di nuova pesata
- [ ] Export Health Connect (peso, body fat, water, bone mass)
- [ ] Sharing PDF report (per dietologo)
- [ ] Tagging proximity (vedi `mondeer-bluetooth-scale-ha`): l'app pubblica
      su MQTT quando vede l'advertising BLE della bilancia, il listener
      attribuisce la pesata all'utente più vicino

## License

MIT (vedi LICENSE del progetto principale).
