# Home Assistant integration

The listener publishes weight readings on MQTT; Home Assistant
consumes them as standard `mqtt.sensor` entities. The repo ships a
ready-made HA package with sensors, helpers, template sensors,
automation and a Lovelace dashboard.

---

## 1. Prerequisites

- A working MQTT broker accessible from Home Assistant. The simplest
  option is the **Mosquitto add-on** that ships with HA OS / HA Supervised.
- The Home Assistant **MQTT integration** must be set up (Settings →
  Devices & Services → MQTT). The credentials there are the same ones
  the listener will use.
- Recommended: `card-mod`, `mushroom-cards`, `gauge-card-pro`,
  `apexcharts-card`, `auto-entities`, `person-tracker-card` from HACS,
  if you want to use the included dashboard as-is.

---

## 2. Install the HA package

Copy the file:

```
home_assistant/packages/bilancia.yaml  →  /config/packages/bilancia.yaml
```

The HA `configuration.yaml` must include packages:

```yaml
homeassistant:
  packages: !include_dir_named packages
```

Restart Home Assistant (or **Reload YAML configuration → All YAML
configuration**, depending on what is needed by the changes).

What this package declares:

- **MQTT sensors** `sensor.peso_<name>` — one per profile in
  `listener/config.yaml`. The sensor's `state` is the weight in kg, and
  the JSON attributes carry `fat_percent`, `water_percent`, `bone_kg`,
  `muscle_kg`, `visceral_fat`, `calorie_kcal`, `bmi`, `is_complete`,
  `measured_at`, and a few derived fields.
- **Helper entities**: `input_select.bilancia_<name>_sesso`,
  `input_number.bilancia_<name>_eta` etc. The listener reads these
  on every connection so the user can edit profiles from the
  dashboard without restarting the listener service.
- **Template sensors** for *italiano* labels and computed metrics
  (BMI, status badges "sotto/ottimale/sopra", monthly delta %, etc.).
- **Automation** `Bilancia · Notifica pesata`: sends a single Telegram
  notification per weighing **only when** the record carries a fresh
  BIA reading (`is_complete: true`). Preliminary records and merged
  ones do not trigger it.

> **You must edit the YAML to match your config.** In particular:
> `chat_id` of the Telegram automation; the profile names if you used
> something other than `user1/user2/user3`; the labels and ranges of
> the `input_number` / `input_select` helpers.

---

## 3. Install the dashboard

```
home_assistant/dashboards/bilancia.yaml  →  /config/dashboards/bilancia.yaml
```

Then add a Lovelace view that includes it (storage-mode dashboards:
edit Lovelace, "Raw configuration editor", paste the contents into a
new view; YAML-mode dashboards: just `!include` it).

The dashboard lays out **3 columns side-by-side**, one per family
profile, each showing:

- a `mushroom-template-card` header with the profile picture and name
- the latest weight + monthly delta
- a `gauge-card-pro` for BMI with optimal-range band
- a `gauge-card-pro` for body fat %, water %, muscle %, with the same
  range bands
- a small `apexcharts-card` history graph (90 days) with the optimal
  range as a fixed annotation
- editable profile inputs (sex, age, height, weight range)

Screenshot:

![dashboard](../screenshots/dashboard-overview.png)

---

## 4. MQTT topics reference

The listener publishes on **one topic per profile**, with QoS 1 and
retain flag set:

```
bilancia_mondeer/peso_<name>/state    JSON, retained
```

Example payload (after a complete weighing):

```json
{
  "weight_kg": 67.9,
  "fat_percent": 28.4,
  "water_percent": 50.4,
  "bone_kg": 2.6,
  "muscle_kg": 21.1,
  "visceral_fat": 10,
  "calorie_kcal": 1611,
  "bmi": 22.9,
  "is_complete": true,
  "measured_at": "2026-04-27T08:15:32",
  "scale_timestamp_unix": 1777264160,
  "weight_30d_ago": 68.4,
  "monthly_change_pct": -0.73,
  "friendly_name": "Peso User1"
}
```

If the user steps off before the BIA finishes, `is_complete` becomes
`false` and the body comp fields are populated from the **last
complete reading** for that profile. The listener stores those values
locally in `last_pushed.json`.

Use this discriminator (`is_complete`) in any automation that fires
on a weighing — that's how the bundled Telegram automation avoids
sending three notifications per weigh-in.

---

## 5. Customising the Telegram message

The automation `bilancia_notifica_pesata` in
`home_assistant/packages/bilancia.yaml` builds a markdown message
with all body composition values plus a 🔵/✅/⚠️ status icon for each
metric (under/optimal/over the configured range). To customise:

- The icons: search for the `icon()` macro in the automation's
  `message:` template.
- The chat_id: replace the placeholder `0` with your actual numeric
  chat_id (use [@userinfobot](https://t.me/userinfobot) on Telegram to
  find it).
- The Telegram bot integration must be set up in HA in advance
  (Settings → Devices & Services → Telegram bot, polling mode is
  fine).

---

## 6. Notes on internationalisation

The dashboard text is in **Italian** (this is, after all, what I run
at home). Renaming labels to your language is a matter of search-and-
replace in `home_assistant/packages/bilancia.yaml` and
`home_assistant/dashboards/bilancia.yaml`. The Python listener has
been kept fully English-language, so the back-end requires no change.
