# WeighAi 1.0.0 — 2026-04-28

Prima release stabile dell'app companion per la bilancia smart Mondeer/WanKa C1
+ listener Python su Home Assistant.

## Componenti

- **Listener Python** (`poc/scale_listener.py`) — gira su minipc Windows,
  riceve advertising BLE dalla bilancia, fa handshake (binding match,
  time sync, family profiles) e pubblica le pesate via MQTT su HA.
- **Pacchetto HA** (`packages/bilancia.yaml`) — sensori `peso_<profilo>`,
  automazioni Telegram inquiry, push notifica WeighAi.
- **App Android** (`android-app/`) — UI futuristica, integrazione Health
  Connect, MQTT live.

## Highlights di questa release

### Listener / protocollo BLE
- Reverse engineering completo della negoziazione: device info → binding
  match (cmd=2 data=104, type=0/subtype=1) → 104 ACK → time sync (data=103)
  → ricevuto data=3 (settings) → invio profili (data=102)
- Recovery `o()` (mimicks `c.java::a(h hVar)`): se arriva un weight frame
  anonymous (user_id=0, age=0, owner_type=1) il listener rimanda i profili
  → la pesata successiva ha BIA completa (un solo "salto" sulla bilancia)
- Pre-pairing al 3° tentativo di connect (Realtek BLE ostico)
- Idle timeout 90s (default era 30s, troppo per BIA con scarso contatto)
- Health-check MQTT ogni 30s (paho-mqtt non auto-reinvia subscribe)
- on_connect re-subscribe (paho-mqtt non lo fa di suo dopo reconnect)
- Triple retry post-Telegram-confirm (publish post-idle veniva perso)
- Dedup ASK Telegram entro 60s (la bilancia manda 2-3 frame complete e
  ognuno avrebbe generato una notifica duplicata)

### App Android — UI
- **Hero3DGauge** (peso): arco 270° con sweep gradient, blur backlight neon
  pulsante reattivo allo status, marker animato con halo, counter count-up
- **MetricRingCard** (fat/water/muscle/bone/visceral): mini ring-gauge
  individuale con backlight blur per ogni metrica, range ottimale evidenziato
- **MeshBackground**: 3 blob radiali animati indipendentemente (9s/13s/17s)
  modulati dal `weightStatus`
- **Trend chart reattivo**: passando da Peso → IMC → Grasso → Acqua →
  Muscoli → Ossa → Viscerale la scala e i punti si ricalcolano
- Etichette stato: Basso / Ottimale / Alto (era Sotto / Ottimale / Sopra)
- Refresh MQTT su `onResume` (HiveMQ tendeva a perdere connessione in Doze)
- Deep link `weighai://main` per push notification HA → click apre app
- Toggle "Mostra password" nel Setup
- Time parsing local-time corretto (era UTC, errore di 2h)

### Integrazione Health Connect
- Toggle "Esporta in Health Connect" in Setup
- Permission contract con `PermissionController.createRequestPermissionResultContract()`
- Manifest: `<uses-permission>` per i 6 record types + `<intent-filter>`
  per `VIEW_PERMISSION_USAGE` + `HEALTH_PERMISSIONS` (necessario per HC
  integrato Android 14+)
- Bottone "Test scrittura" — diagnostica immediata
- Bottone "Sincronizza ultimi 30 giorni" — backfill da Room → HC
- Pannello "Debug log" in-app (copia/pulisci) per troubleshooting senza adb

### Limiti noti
- **Storico HA non importato**: il backfill HC esporta solo le pesate
  presenti in Room (cioè quelle ricevute via MQTT da quando l'app è
  installata). Le pesate fatte prima dell'install rimangono solo su HA.
  → Prossima release: REST import da `/api/history/period/...`

### Stack
- Listener: Python 3.12 + bleak + paho-mqtt
- App: Kotlin 2.2.10 + Compose + Hilt 2.56 + Room 2.7.2 + HiveMQ MQTT 1.3.7
  + Health Connect 1.1.0 stable
- Build: AGP 9.1.1 + Gradle 9.3.1, JDK 21 (Android Studio JBR), targetSdk 36

## Hardware riferimento
- Bilancia: WanKa C1 (08:7C:BE:87:34:71)
- Listener PC: minipc Intel 7260 BT integrato, Windows 11
- Telefono: Samsung Galaxy S24 (Android 14, One UI 6.1)
- HA: Home Assistant @ 192.168.1.190
