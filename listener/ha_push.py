"""Push pesate verso Home Assistant via MQTT con auto-discovery.

Vantaggio MQTT vs REST: le entità persistono ai restart HA (broker mantiene
i topic retained), e HA crea l'entity automaticamente alla prima discovery.

Topics:
  Discovery: homeassistant/sensor/peso_<name>/config       (retained)
  State:     bilancia_mondeer/peso_<name>/state            (retained, JSON)

Lo storico (per scostamento mensile %) viene letto via REST API HA.
"""
from __future__ import annotations

import json
import logging
import os
import threading
import urllib.request
import urllib.error
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional

import paho.mqtt.client as mqtt

# Tutte le credenziali e i path arrivano da config.yaml (o da env var
# BILANCIA_*). Vedi config.example.yaml per il template. Le costanti
# legacy (HA_BASE_URL/HA_TOKEN/MQTT_*) restano accessibili come
# module-level via PEP 562 `__getattr__` solo per backward-compat.
from .config import get_config


def __getattr__(name: str):  # PEP 562
    cfg = get_config()
    if name == "HA_BASE_URL":
        return cfg.ha_base_url
    if name == "HA_TOKEN":
        return cfg.ha_token
    if name == "MQTT_HOST":
        return cfg.mqtt_host
    if name == "MQTT_PORT":
        return cfg.mqtt_port
    if name == "MQTT_USER":
        return cfg.mqtt_user
    if name == "MQTT_PASS":
        return cfg.mqtt_pass
    if name == "STATE_DIR":
        return Path(cfg.state_dir).expanduser()
    if name == "STATE_FILE":
        return Path(cfg.state_dir).expanduser() / "last_pushed.json"
    raise AttributeError(f"module 'ha_push' has no attribute {name!r}")

log = logging.getLogger("ha_push")

_mqtt_client: Optional[mqtt.Client] = None
_mqtt_lock = threading.Lock()

# Inquiry callbacks: (inquiry_id) → callback(answer_dict)
_inquiry_callbacks: dict[str, "callable"] = {}
_inquiry_lock = threading.Lock()


def _state_dir() -> Path:
    """Used by classifier.py to locate training_data.json."""
    return STATE_DIR


# ---------------------------------------------------------------------------
# Stato locale anti-duplicati
# ---------------------------------------------------------------------------

def _load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except Exception as e:
        log.warning("could not load state: %r", e)
        return {}


def _save_state(state: dict) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    tmp = STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, indent=2), encoding="utf-8")
    tmp.replace(STATE_FILE)


def _entry(state: dict, profile_name: str) -> dict:
    """Estrae l'entry per profilo, con backward-compat dal formato vecchio
    (intero timestamp) al nuovo (dict con ts + body_comp)."""
    raw = state.get(profile_name)
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, int):
        return {"ts": raw}
    return {}


def already_pushed(profile_name: str, timestamp_unix: int) -> bool:
    state = _load_state()
    last = _entry(state, profile_name).get("ts", 0)
    return timestamp_unix <= last


def mark_pushed(profile_name: str, timestamp_unix: int,
                body_comp: Optional[dict] = None) -> None:
    """Memorizza l'ultimo push. Se body_comp è fornito (record completo)
    viene salvato per merge su record futuri preliminari."""
    state = _load_state()
    entry = _entry(state, profile_name)
    entry["ts"] = timestamp_unix
    if body_comp:
        entry["body_comp"] = body_comp
    state[profile_name] = entry
    _save_state(state)


def get_last_body_comp(profile_name: str) -> Optional[dict]:
    """Restituisce il body comp dell'ultimo push completo, se esiste."""
    state = _load_state()
    return _entry(state, profile_name).get("body_comp")


# ---------------------------------------------------------------------------
# Storico via REST API (scostamento mensile)
# ---------------------------------------------------------------------------

def fetch_weight_around(profile_name: str, days_ago: int = 30) -> Optional[float]:
    end = datetime.now()
    start = end - timedelta(days=days_ago)
    window_start = start - timedelta(days=3)
    window_end = start + timedelta(days=3)
    entity_id = f"sensor.peso_{profile_name}"
    url = (
        f"{HA_BASE_URL}/api/history/period/{window_start.isoformat()}"
        f"?filter_entity_id={entity_id}"
        f"&end_time={window_end.isoformat()}"
        f"&minimal_response&no_attributes"
    )
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {HA_TOKEN}"})
    try:
        with urllib.request.urlopen(req, timeout=5.0) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        log.warning("history fetch failed: %r", e)
        return None
    if not data or not data[0]:
        return None
    target = start.timestamp()
    best_val = None
    best_dist = float("inf")
    for s in data[0]:
        st = s.get("state")
        if st in (None, "unknown", "unavailable"):
            continue
        try:
            v = float(st)
        except (TypeError, ValueError):
            continue
        ts_str = s.get("last_changed") or s.get("last_updated") or ""
        try:
            ts = datetime.fromisoformat(ts_str.replace("Z", "+00:00")).timestamp()
        except Exception:
            continue
        d = abs(ts - target)
        if d < best_dist:
            best_dist = d
            best_val = v
    return best_val


# ---------------------------------------------------------------------------
# MQTT
# ---------------------------------------------------------------------------

def _on_inquiry_answer(client, userdata, msg) -> None:
    """Called when HA publishes a Telegram-confirmed answer back to us.

    IMPORTANTE: questo handler gira sul thread di paho-mqtt (loop_start).
    Se la callback invocata dentro fa altre op MQTT (publish + wait), si
    crea un deadlock: il thread di paho aspetta se stesso. Eseguiamo la
    callback in un thread separato per evitarlo.
    """
    try:
        # topic: bilancia_mondeer/inquiry/<id>/answer
        parts = msg.topic.split("/")
        if len(parts) < 4:
            return
        inquiry_id = parts[2]
        try:
            payload = json.loads(msg.payload.decode("utf-8"))
        except Exception:
            payload = {"name": msg.payload.decode("utf-8").strip()}
        log.info("inquiry answer received: id=%s payload=%s", inquiry_id, payload)
        with _inquiry_lock:
            cb = _inquiry_callbacks.pop(inquiry_id, None)
        if cb:
            def _run_cb():
                try:
                    cb(payload)
                except Exception as e:
                    log.error("inquiry callback error: %r", e)
            threading.Thread(
                target=_run_cb, name=f"inquiry-cb-{inquiry_id}", daemon=True
            ).start()
        else:
            log.warning("no callback registered for inquiry %s", inquiry_id)
    except Exception as e:
        log.error("error handling inquiry answer: %r", e)


def _reset_mqtt() -> None:
    """Forza la ricreazione del client MQTT al prossimo `_get_mqtt`."""
    global _mqtt_client
    with _mqtt_lock:
        if _mqtt_client is not None:
            try:
                _mqtt_client.loop_stop()
                _mqtt_client.disconnect()
            except Exception:
                pass
        _mqtt_client = None


def _get_mqtt() -> mqtt.Client:
    global _mqtt_client
    import time as _t
    t0 = _t.monotonic()
    log.debug("[mqtt] _get_mqtt() ENTER tid=%s",
              threading.current_thread().name)
    with _mqtt_lock:
        t_lock = _t.monotonic() - t0
        if t_lock > 0.5:
            log.warning("[mqtt] lock acquired after %.1fs (contention)", t_lock)
        if _mqtt_client is not None and _mqtt_client.is_connected():
            log.debug("[mqtt] returning existing client (connected)")
            return _mqtt_client
        if _mqtt_client is not None:
            log.info("[mqtt] existing client not connected, dropping")
            try:
                _mqtt_client.loop_stop()
                _mqtt_client.disconnect()
            except Exception as e:
                log.debug("[mqtt] disconnect old failed: %r", e)
            _mqtt_client = None
        log.info("[mqtt] creating new client")
        client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"bilancia_listener_{os.getpid()}",
        )
        client.username_pw_set(MQTT_USER, MQTT_PASS)
        client.reconnect_delay_set(min_delay=1, max_delay=30)

        def _on_connect(c, userdata, flags, rc, properties=None):
            log.info("MQTT on_connect rc=%s — (re)subscribing to inquiry/+/answer", rc)
            try:
                c.subscribe("bilancia_mondeer/inquiry/+/answer", qos=1)
            except Exception as e:
                log.error("re-subscribe failed: %r", e)
        client.on_connect = _on_connect
        client.message_callback_add("bilancia_mondeer/inquiry/+/answer",
                                    _on_inquiry_answer)

        # IMPORTANTE: client.connect() di paho è SINCRONO e blocca su TCP
        # fino al timeout di sistema (~70s) se il broker non risponde.
        # Settiamo il socket timeout via _socket_timeout (paho >=1.6) per
        # avere un fail veloce.
        try:
            log.info("[mqtt] connecting to %s:%d ...", MQTT_HOST, MQTT_PORT)
            t1 = _t.monotonic()
            client.connect(MQTT_HOST, MQTT_PORT, keepalive=60)
            log.info("[mqtt] connect() returned in %.1fs", _t.monotonic() - t1)
        except Exception as e:
            log.error("[mqtt] connect() FAILED: %r — propagating", e)
            raise
        client.subscribe("bilancia_mondeer/inquiry/+/answer", qos=1)
        client.loop_start()
        _mqtt_client = client
        log.info("MQTT connected to %s:%d (subscribed inquiry answers) — "
                 "total init=%.1fs", MQTT_HOST, MQTT_PORT,
                 _t.monotonic() - t0)
        return client


def publish_inquiry(inquiry_id: str, record_dict: dict, predicted: Optional[str],
                    confidence: float, reason: str, callback) -> bool:
    """Publish an open question on MQTT: 'who stepped on the scale?'.

    HA will pick up the message via an automation, send a Telegram inline
    keyboard, and publish the chosen user back to
    ``bilancia_mondeer/inquiry/<id>/answer``. The provided ``callback``
    will be invoked with the parsed answer payload.
    """
    with _inquiry_lock:
        _inquiry_callbacks[inquiry_id] = callback
    topic = f"bilancia_mondeer/inquiry/{inquiry_id}/state"
    payload = {
        "inquiry_id": inquiry_id,
        "ts": datetime.now().isoformat(timespec="seconds"),
        "predicted": predicted,
        "confidence": round(confidence, 3),
        "reason": reason,
        "weight_kg": record_dict.get("weight_kg"),
        "fat_pct": record_dict.get("fat_pct"),
        "water_pct": record_dict.get("water_pct"),
        "bone_kg": record_dict.get("bone_kg"),
        "muscle_kg": record_dict.get("muscle_kg"),
        "visceral_fat": record_dict.get("visceral_fat"),
        "bmi": record_dict.get("bmi"),
    }
    try:
        info = _get_mqtt().publish(topic, json.dumps(payload), qos=1, retain=False)
        info.wait_for_publish(timeout=5)
        if info.is_published():
            log.info("inquiry %s published (predicted=%s, conf=%.2f)",
                     inquiry_id, predicted, confidence)
            return True
        log.error("inquiry %s publish failed", inquiry_id)
        return False
    except Exception as e:
        log.error("inquiry %s publish error: %r", inquiry_id, e)
        return False


def cancel_inquiry(inquiry_id: str) -> None:
    """Drop a pending inquiry callback (e.g. on timeout)."""
    with _inquiry_lock:
        _inquiry_callbacks.pop(inquiry_id, None)


# ---------------------------------------------------------------------------
# Debug log via MQTT — pubblica le ultime N righe del log su un topic retained
# così posso leggere via REST API HA (`/api/states/sensor.bilancia_listener_log`)
# senza dover scaricare il listener.log dal minipc ogni volta.
# ---------------------------------------------------------------------------

_LOG_TOPIC = "bilancia_mondeer/listener/recent_log"
_ERR_TOPIC = "bilancia_mondeer/listener/errors"
_STATUS_TOPIC = "bilancia_mondeer/listener/status"


def publish_recent_log(lines: list) -> None:
    """Publish the last N log lines as a retained JSON payload."""
    try:
        client = _get_mqtt()
        payload = {
            "ts": datetime.now().isoformat(timespec="seconds"),
            "n_lines": len(lines),
            "lines": lines,
        }
        client.publish(_LOG_TOPIC, json.dumps(payload), qos=0, retain=True)
    except Exception:
        pass


def publish_errors(error_entries: list) -> None:
    """Publish the last N WARNING/ERROR entries (with stack traces if any).

    Each entry is a dict {ts, level, name, msg, traceback (optional)}.
    """
    try:
        client = _get_mqtt()
        payload = {
            "ts": datetime.now().isoformat(timespec="seconds"),
            "n": len(error_entries),
            "errors": error_entries,
        }
        client.publish(_ERR_TOPIC, json.dumps(payload), qos=1, retain=True)
    except Exception:
        pass


def publish_status(status_dict: dict) -> None:
    """Publish a compact status snapshot (called periodically)."""
    try:
        client = _get_mqtt()
        status_dict.setdefault("ts", datetime.now().isoformat(timespec="seconds"))
        client.publish(_STATUS_TOPIC, json.dumps(status_dict), qos=0, retain=True)
    except Exception:
        pass


def _state_topic(profile_name: str) -> str:
    return f"bilancia_mondeer/peso_{profile_name}/state"


def push_weight(profile_name: str, weight) -> bool:
    """Pubblica lo state del peso su MQTT.

    Le entity HA (sensor.peso_<n>) sono definite nel package YAML
    bilancia.yaml, non serve auto-discovery.
    """
    weight_30d = fetch_weight_around(profile_name, days_ago=30)
    if weight_30d and weight_30d > 0:
        monthly_pct = round(((weight.weight_kg - weight_30d) / weight_30d) * 100, 2)
    else:
        monthly_pct = None

    is_complete = weight.fat_pct is not None
    body_comp = {
        "fat_percent": weight.fat_pct,
        "water_percent": weight.water_pct,
        "bone_kg": weight.bone_kg,
        "muscle_kg": weight.muscle_kg,
        "visceral_fat": weight.visceral_fat,
        "calorie_kcal": weight.calorie_kcal,
        "bmi": weight.bmi,
    }
    # Record preliminare (utente sceso prima della bioimpedenziometria):
    # riusa l'ultimo body comp memorizzato per non lasciare la dashboard vuota.
    if not is_complete:
        last = get_last_body_comp(profile_name)
        if last:
            log.info("preliminary record for %s: merging body comp from last push",
                     profile_name)
            body_comp = last

    state = {
        "weight_kg": round(weight.weight_kg, 1),
        **body_comp,
        "is_complete": is_complete,
        "measured_at": datetime.now().isoformat(timespec="seconds"),
        "scale_timestamp_unix": weight.timestamp_unix,
        "weight_30d_ago": weight_30d,
        "monthly_change_pct": monthly_pct,
        "friendly_name": f"Peso {profile_name.replace('_', ' ').title()}",
    }

    payload = json.dumps(state)
    topic = _state_topic(profile_name)
    import time as _time
    import threading as _threading
    log.info("push_weight ENTER %s tid=%s is_complete=%s",
             profile_name, _threading.current_thread().name, state["is_complete"])
    for attempt in (1, 2, 3):
        try:
            log.info("push_weight attempt=%d: getting MQTT client", attempt)
            client = _get_mqtt()
            log.info("push_weight attempt=%d: got client connected=%s",
                     attempt, client.is_connected())
            if attempt > 1:
                _time.sleep(1.5)
            log.info("push_weight attempt=%d: publishing", attempt)
            info = client.publish(topic, payload, qos=1, retain=True)
            log.info("push_weight attempt=%d: waiting for publish ack (10s)", attempt)
            info.wait_for_publish(timeout=10)
            if info.is_published():
                log.info("MQTT publish %s OK (weight=%.1fkg, mid=%d, attempt=%d)",
                         profile_name, weight.weight_kg, info.mid, attempt)
                return True
            log.warning("MQTT publish %s timeout on attempt %d, resetting client",
                        profile_name, attempt)
            _reset_mqtt()
            log.info("push_weight attempt=%d: client reset, will retry", attempt)
        except Exception as e:
            log.warning("MQTT publish %s exception on attempt %d: %r",
                        profile_name, attempt, e)
            _reset_mqtt()
    log.error("MQTT publish %s failed after 3 attempts", profile_name)
    return False
