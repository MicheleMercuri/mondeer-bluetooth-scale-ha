"""Configuration loader.

Reads ``config.yaml`` next to the listener code (or an absolute path passed
via the ``BILANCIA_CONFIG`` environment variable). Each value can be
overridden by a ``BILANCIA_*`` environment variable: this is convenient when
running under a service manager that injects secrets via env (systemd,
Docker, Windows Task Scheduler with custom env, etc.) without leaving
credentials on disk.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

try:
    import yaml  # PyYAML
except ImportError as e:  # pragma: no cover
    raise SystemExit(
        "PyYAML is required: pip install -r listener/requirements.txt"
    ) from e


@dataclass
class Profile:
    """Family member profile sent to the scale."""
    name: str           # internal slug (a-z0-9_), used in MQTT topic
    user_id: int        # 1..N, 1 must be the admin
    sex: int            # 1=male, 0=female
    age: int
    height_cm: int
    weight_min: float   # used for ambiguous-match fallback when scale sends
    weight_max: float   # a "preliminary" record without sex/age/height
    is_admin: bool = False


@dataclass
class Config:
    # Home Assistant REST (optional - only for monthly delta % lookup)
    ha_base_url: str = ""
    ha_token: str = ""

    # MQTT broker
    mqtt_host: str = "localhost"
    mqtt_port: int = 1883
    mqtt_user: str = ""
    mqtt_pass: str = ""

    # Family identifier sent to the scale during binding
    family_id: int = 1

    # Where to keep last_pushed.json and metrics.json
    state_dir: str = "~/.local/state/bilancia-mondeer"

    # BLE auto-recovery (Windows-specific). Restart the bthserv service
    # after N consecutive failed connect sessions. Disabled by default
    # because it requires admin privileges.
    enable_auto_recovery: bool = False
    recovery_after_n_failed_sessions: int = 3

    # Profiles. If empty the listener will try to load them from HA helpers
    # named input_select.bilancia_<name>_sesso, input_number.bilancia_<name>_eta
    # etc. (see home_assistant/packages/bilancia.yaml).
    profiles: list = field(default_factory=list)


_cached: Optional[Config] = None


def _config_path() -> Path:
    env = os.environ.get("BILANCIA_CONFIG")
    if env:
        return Path(env).expanduser()
    return Path(__file__).resolve().parent / "config.yaml"


def _coerce_profiles(raw: list) -> list:
    out = []
    for i, p in enumerate(raw or [], start=1):
        out.append(Profile(
            name=p["name"],
            user_id=int(p.get("user_id", i)),
            sex=int(p["sex"]),
            age=int(p["age"]),
            height_cm=int(p["height_cm"]),
            weight_min=float(p["weight_min"]),
            weight_max=float(p["weight_max"]),
            is_admin=bool(p.get("is_admin", i == 1)),
        ))
    return out


def get_config() -> Config:
    global _cached
    if _cached is not None:
        return _cached

    cfg_path = _config_path()
    raw = {}
    if cfg_path.exists():
        with cfg_path.open("r", encoding="utf-8") as f:
            raw = yaml.safe_load(f) or {}

    cfg = Config(
        ha_base_url=os.environ.get("BILANCIA_HA_BASE_URL", raw.get("ha_base_url", "")),
        ha_token=os.environ.get("BILANCIA_HA_TOKEN", raw.get("ha_token", "")),
        mqtt_host=os.environ.get("BILANCIA_MQTT_HOST", raw.get("mqtt_host", "localhost")),
        mqtt_port=int(os.environ.get("BILANCIA_MQTT_PORT", raw.get("mqtt_port", 1883))),
        mqtt_user=os.environ.get("BILANCIA_MQTT_USER", raw.get("mqtt_user", "")),
        mqtt_pass=os.environ.get("BILANCIA_MQTT_PASS", raw.get("mqtt_pass", "")),
        family_id=int(raw.get("family_id", 1)),
        state_dir=os.environ.get(
            "BILANCIA_STATE_DIR",
            raw.get("state_dir", "~/.local/state/bilancia-mondeer"),
        ),
        enable_auto_recovery=bool(raw.get("enable_auto_recovery", False)),
        recovery_after_n_failed_sessions=int(
            raw.get("recovery_after_n_failed_sessions", 3)
        ),
        profiles=_coerce_profiles(raw.get("profiles", [])),
    )
    _cached = cfg
    return cfg
