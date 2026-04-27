"""Push weight readings to Home Assistant via MQTT.

MQTT was preferred over the direct HA REST API because the broker keeps
retained state across HA restarts (a transient REST POST disappears if HA
reboots before the next reading). The historical lookup for monthly delta %
still uses REST for convenience.

Topics:
  bilancia_mondeer/peso_<profile_name>/state    (retained, JSON)

The MQTT sensor entities are declared once in the HA package
``home_assistant/packages/bilancia.yaml``. No discovery payload is published.
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

from .config import get_config

log = logging.getLogger("ha_push")

_mqtt_client: Optional[mqtt.Client] = None
_mqtt_lock = threading.Lock()


# ---------------------------------------------------------------------------
# Local anti-duplicate state + body comp memory
# ---------------------------------------------------------------------------

def _state_dir() -> Path:
    cfg = get_config()
    return Path(cfg.state_dir).expanduser()


def _state_file() -> Path:
    return _state_dir() / "last_pushed.json"


def _load_state() -> dict:
    try:
        return json.loads(_state_file().read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except Exception as e:
        log.warning("could not load state: %r", e)
        return {}


def _save_state(state: dict) -> None:
    _state_dir().mkdir(parents=True, exist_ok=True)
    tmp = _state_file().with_suffix(".tmp")
    tmp.write_text(json.dumps(state, indent=2), encoding="utf-8")
    tmp.replace(_state_file())


def _entry(state: dict, profile_name: str) -> dict:
    """Extract per-profile entry, with backward-compat: older versions stored
    just an integer timestamp; newer ones store dict {ts, body_comp}."""
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
    """Persist the last pushed timestamp. If ``body_comp`` is provided
    (= record had a complete BIA), it is stored for later merge into
    preliminary records (where BIA fields are absent)."""
    state = _load_state()
    entry = _entry(state, profile_name)
    entry["ts"] = timestamp_unix
    if body_comp:
        entry["body_comp"] = body_comp
    state[profile_name] = entry
    _save_state(state)


def get_last_body_comp(profile_name: str) -> Optional[dict]:
    """Return the body composition from the last complete push, or None."""
    state = _load_state()
    return _entry(state, profile_name).get("body_comp")


# ---------------------------------------------------------------------------
# Historical lookup via HA REST API (for monthly delta %)
# ---------------------------------------------------------------------------

def fetch_weight_around(profile_name: str, days_ago: int = 30) -> Optional[float]:
    cfg = get_config()
    if not cfg.ha_base_url or not cfg.ha_token:
        return None
    end = datetime.now()
    start = end - timedelta(days=days_ago)
    window_start = start - timedelta(days=3)
    window_end = start + timedelta(days=3)
    entity_id = f"sensor.peso_{profile_name}"
    url = (
        f"{cfg.ha_base_url}/api/history/period/{window_start.isoformat()}"
        f"?filter_entity_id={entity_id}"
        f"&end_time={window_end.isoformat()}"
        f"&minimal_response&no_attributes"
    )
    req = urllib.request.Request(
        url, headers={"Authorization": f"Bearer {cfg.ha_token}"}
    )
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
# MQTT publisher (singleton client, lazy connect)
# ---------------------------------------------------------------------------

def _get_mqtt() -> mqtt.Client:
    global _mqtt_client
    with _mqtt_lock:
        if _mqtt_client is not None and _mqtt_client.is_connected():
            return _mqtt_client
        cfg = get_config()
        client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"bilancia_listener_{os.getpid()}",
        )
        if cfg.mqtt_user:
            client.username_pw_set(cfg.mqtt_user, cfg.mqtt_pass or "")
        client.connect(cfg.mqtt_host, cfg.mqtt_port, keepalive=60)
        client.loop_start()
        _mqtt_client = client
        log.info("MQTT connected to %s:%d", cfg.mqtt_host, cfg.mqtt_port)
        return client


def _state_topic(profile_name: str) -> str:
    return f"bilancia_mondeer/peso_{profile_name}/state"


def push_weight(profile_name: str, weight) -> bool:
    """Publish the weight reading on the MQTT state topic.

    HA entities (``sensor.peso_<name>``) are declared via the YAML package,
    no auto-discovery payload is sent.
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
    # Preliminary record (user stepped off before BIA finished): reuse the
    # body composition from the last complete push so the dashboard does not
    # blank out. The ``is_complete`` flag in the payload lets HA automations
    # distinguish fresh BIA readings from merged ones.
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

    try:
        info = _get_mqtt().publish(
            _state_topic(profile_name), json.dumps(state), qos=1, retain=True
        )
        info.wait_for_publish(timeout=5)
        if info.is_published():
            log.info("MQTT publish %s OK (weight=%.1fkg, mid=%d)",
                     profile_name, weight.weight_kg, info.mid)
            return True
        log.error("MQTT publish %s failed: not published in time", profile_name)
        return False
    except Exception as e:
        log.error("MQTT publish %s failed: %r", profile_name, e)
        return False
