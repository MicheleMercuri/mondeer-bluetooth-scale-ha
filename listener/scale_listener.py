"""Listener BLE bilancia Mondeer (POC).

La bilancia si accende solo durante la pesata e si spegne dopo pochi
secondi. Per intercettare la misura serve uno scanner permanente che si
attacchi al volo quando vede l'advertising. Quando la bilancia si spegne
il client si disconnette e torna in scan.

Uso: python scale_listener.py
Stop: Ctrl+C
"""
from __future__ import annotations

import asyncio
import logging
import sys
import threading
from typing import Optional

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData

sys.path.insert(0, str(__import__("pathlib").Path(__file__).parent))
from .parser import (
    parse_ble_packet,
    parse_weight_payload,
    parse_all_weight_records,
    FrameReassembler,
    LogicalFrame,
    build_packets,
    build_ad_payload,
    build_ae_payload,
    build_ab_payload,
    build_ack_packet,
)
from .ha_push import (
    push_weight, already_pushed, mark_pushed,
    publish_inquiry, cancel_inquiry,
)
from .classifier import classify, save_sample, MIN_SAMPLES_PER_USER, CONFIDENCE_THRESHOLD

FAMILY_PROFILES = [
    {"user_id": 1, "name": "user1",     "sex": 1, "age": 52, "height": 172, "wmin": 61, "wmax": 69, "is_admin": True},
    {"user_id": 2, "name": "user2", "sex": 0, "age": 51, "height": 169, "wmin": 49, "wmax": 55, "is_admin": False},
    {"user_id": 3, "name": "user3",     "sex": 0, "age": 14, "height": 155, "wmin": 53, "wmax": 60, "is_admin": False},
]
FAMILY_ID = 1


def refresh_profiles_from_ha() -> None:
    """Aggiorna FAMILY_PROFILES leggendo gli helpers HA (input_number / input_select).

    Se la chiamata fallisce, mantiene i defaults hardcoded.
    """
    import urllib.request
    import json as _json
    from .ha_push import HA_BASE_URL, HA_TOKEN

    def _state(entity_id):
        url = f"{HA_BASE_URL}/api/states/{entity_id}"
        req = urllib.request.Request(url, headers={"Authorization": f"Bearer {HA_TOKEN}"})
        with urllib.request.urlopen(req, timeout=3.0) as r:
            return _json.loads(r.read())["state"]

    for p in FAMILY_PROFILES:
        n = p["name"]
        try:
            sex_str = _state(f"input_select.bilancia_{n}_sesso")
            p["sex"] = 1 if sex_str.upper() == "M" else 0
            p["age"] = int(float(_state(f"input_number.bilancia_{n}_eta")))
            p["height"] = int(float(_state(f"input_number.bilancia_{n}_altezza")))
            p["wmin"] = float(_state(f"input_number.bilancia_{n}_peso_min"))
            p["wmax"] = float(_state(f"input_number.bilancia_{n}_peso_max"))
            log.info("profile %s loaded from HA: sex=%d age=%d h=%d w=%g-%g",
                     n, p["sex"], p["age"], p["height"], p["wmin"], p["wmax"])
        except Exception as e:
            log.warning("profile %s: HA fetch failed (%s), using default", n, e)


def match_profile(record) -> str:
    """Identifica il familiare confrontando sex+age+height con FAMILY_PROFILES.

    Se sex/age/height sono tutti 0 (record peso "preliminare" senza
    body composition completata), fallback su range peso wmin/wmax.
    """
    for p in FAMILY_PROFILES:
        if (record.sex == p["sex"]
                and record.age == p["age"]
                and record.height_cm == p["height"]):
            return p["name"]
    # Fallback: record senza profilo associato → match per range peso
    if record.sex == 0 and record.age == 0 and record.height_cm == 0:
        candidates = [p for p in FAMILY_PROFILES
                      if p["wmin"] <= record.weight_kg <= p["wmax"]]
        if len(candidates) == 1:
            log.info("match by weight range only: %s (%.1fkg in [%g,%g])",
                     candidates[0]["name"], record.weight_kg,
                     candidates[0]["wmin"], candidates[0]["wmax"])
            return candidates[0]["name"]
        if len(candidates) > 1:
            log.warning("ambiguous weight match for %.1fkg: %s",
                        record.weight_kg,
                        [c["name"] for c in candidates])
    return f"unknown(sex={record.sex},age={record.age},h={record.height_cm})"

SERVICE_UUID = "0000cc08-0000-1000-8000-00805f9b34fb"
NAME_HINTS = ("mondeer", "scale", "cheng", "ce-link", "celink", "wanka")
RECONNECT_COOLDOWN_S = 1.5
# La bilancia si spegne in ~10s totali. Timeout corto + più tentativi:
# alterniamo tentativi rapidi (per cogliere la finestra in cui il chip è
# pronto) e un pre-pair al 3° tentativo (forza Windows a registrare il
# bond, abbattendo il "pairing trasparente" da 30s a ~2s).
CONNECT_TIMEOUT_S = 8.0
CONNECT_MAX_ATTEMPTS = 6

WAKE_PACKET = bytes([
    0x03, 0x04, 0x02, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
])

try:
    sys.stdout.reconfigure(line_buffering=True)
except Exception:
    pass

import os
from logging.handlers import RotatingFileHandler

_log_dir = os.environ.get("LOCALAPPDATA", os.path.expanduser("~"))
_log_dir = os.path.join(_log_dir, "BilanciaMondeer")
os.makedirs(_log_dir, exist_ok=True)
_log_file = os.path.join(_log_dir, "listener.log")

# Cartella mirror su Google Drive: il listener scrive log+metrics ANCHE qui,
# così posso leggerli da un altro PC senza accesso fisico al minipc.
# Auto-localizzata: il poc è in <drive>:\Il mio Drive\013. APP HA PYTHON\bilancia mondeer\poc\
# La mirror è la sibling cartella runtime-mipc/.
_mirror_dir = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "runtime-mipc"
))
try:
    os.makedirs(_mirror_dir, exist_ok=True)
    _mirror_log = os.path.join(_mirror_dir, "listener.log")
except Exception:
    _mirror_log = None

_root = logging.getLogger()
_root.setLevel(logging.INFO)
_root.handlers.clear()
_fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s")
_fh = RotatingFileHandler(_log_file, maxBytes=2_000_000, backupCount=3, encoding="utf-8")
_fh.setFormatter(_fmt)
_root.addHandler(_fh)
if _mirror_log:
    try:
        _fh_mirror = RotatingFileHandler(_mirror_log, maxBytes=2_000_000,
                                         backupCount=3, encoding="utf-8")
        _fh_mirror.setFormatter(_fmt)
        _root.addHandler(_fh_mirror)
    except Exception:
        pass
if sys.stdout and getattr(sys.stdout, "isatty", lambda: False)():
    _ch = logging.StreamHandler(sys.stdout)
    _ch.setFormatter(_fmt)
    _root.addHandler(_ch)

log = logging.getLogger("mondeer")


# ---------------------------------------------------------------------------
# Debug log via MQTT: ogni log INFO+ viene anche pubblicato su un topic
# MQTT retained con le ultime 80 righe. Permette di leggere lo stato del
# listener da remoto via HA REST API senza scaricare il file di log.
# ---------------------------------------------------------------------------
import time as _time_for_log
import threading as _threading_for_log
import traceback as _traceback_for_log
from collections import deque

_recent_log_buffer = deque(maxlen=80)
_recent_log_lock = _threading_for_log.Lock()
_last_publish_time = [0.0]

_error_buffer = deque(maxlen=20)
_error_buffer_lock = _threading_for_log.Lock()


class MQTTLogHandler(logging.Handler):
    """Forward every log line to MQTT.

    Pubblica AD OGNI log INFO+ (qos=0, retained) — il broker mosquitto può
    facilmente gestire decine di publish/sec. Il debouncer precedente
    collassava troppo i log durante i picchi di startup/handshake e
    rendeva il debug remoto inutile.

    Errori (WARNING+) vanno anche su un topic dedicato con stack trace."""

    def emit(self, record):
        try:
            msg = self.format(record)
            with _recent_log_lock:
                _recent_log_buffer.append(msg)
                lines = list(_recent_log_buffer)
            # Errori in buffer dedicato + traceback (se presente).
            if record.levelno >= logging.WARNING:
                tb = None
                if record.exc_info:
                    tb = "".join(_traceback_for_log.format_exception(*record.exc_info))
                err_entry = {
                    "ts": _dt.now().isoformat(timespec="seconds"),
                    "level": record.levelname,
                    "name": record.name,
                    "msg": record.getMessage(),
                }
                if tb:
                    err_entry["traceback"] = tb
                with _error_buffer_lock:
                    _error_buffer.append(err_entry)
                    err_list = list(_error_buffer)
                try:
                    from .ha_push import publish_errors
                    publish_errors(err_list)
                except Exception:
                    pass
            # Publish ogni log line (no debouncer).
            try:
                from .ha_push import publish_recent_log
                publish_recent_log(lines)
            except Exception:
                pass
        except Exception:
            pass


# NB: l'MQTTLogHandler era qui ma è stato disabilitato all'avvio (registrato
# DOPO che main() inizializza il client MQTT, vedi _enable_mqtt_log_handler()
# chiamato dentro main(), in modo da evitare deadlock al primo log.info che
# proverebbe a connettersi al broker prima che il client sia pronto).
_mqtt_log = MQTTLogHandler()
_mqtt_log.setFormatter(_fmt)
_mqtt_log.setLevel(logging.INFO)


def _enable_mqtt_log_handler() -> None:
    """Attiva l'MQTT log forwarding DOPO che il client MQTT è già stato
    creato la prima volta. Evita deadlock al primo log.info."""
    if _mqtt_log not in _root.handlers:
        _root.addHandler(_mqtt_log)
        log.info("MQTT log handler attivato")

# ---------------------------------------------------------------------------
# Metriche e auto-recovery BT
# ---------------------------------------------------------------------------

import json as _json_mod
from datetime import datetime as _dt

_METRICS_FILE = os.path.join(_log_dir, "metrics.json")

_METRICS = {
    "consecutive_failed_sessions": 0,
    "total_connects_attempted": 0,
    "total_connects_succeeded": 0,
    "total_disconnects": 0,
    "total_recoveries": 0,
    "last_failure_iso": None,
    "last_recovery_iso": None,
    "session_history": [],  # ultime 50 sessioni
}


def _metrics_load() -> None:
    try:
        with open(_METRICS_FILE, "r", encoding="utf-8") as f:
            saved = _json_mod.load(f)
        _METRICS.update({k: v for k, v in saved.items() if k in _METRICS})
    except FileNotFoundError:
        pass
    except Exception as e:
        log.warning("metrics load failed: %r", e)


def _metrics_save() -> None:
    try:
        with open(_METRICS_FILE, "w", encoding="utf-8") as f:
            _json_mod.dump(_METRICS, f, indent=2)
    except Exception as e:
        log.warning("metrics save failed: %r", e)
    # Mirror su Google Drive (best-effort, ignora errori). Drive sync delay
    # è ~30s ma è ok per debug post-mortem.
    if _mirror_log:
        try:
            _mirror_metrics = os.path.join(_mirror_dir, "metrics.json")
            with open(_mirror_metrics, "w", encoding="utf-8") as f:
                _json_mod.dump(_METRICS, f, indent=2)
        except Exception:
            pass


def _metrics_record_session(success: bool, attempts: int, duration_s: float) -> None:
    _METRICS["total_connects_attempted"] += attempts
    if success:
        _METRICS["total_connects_succeeded"] += 1
        _METRICS["consecutive_failed_sessions"] = 0
    else:
        _METRICS["consecutive_failed_sessions"] += 1
        _METRICS["last_failure_iso"] = _dt.now().isoformat(timespec="seconds")
    history = _METRICS.setdefault("session_history", [])
    history.append({
        "ts": _dt.now().isoformat(timespec="seconds"),
        "ok": success,
        "attempts": attempts,
        "duration_s": round(duration_s, 1),
    })
    if len(history) > 50:
        del history[: len(history) - 50]
    _metrics_save()


_metrics_load()


# ---------------------------------------------------------------------------
# Status snapshot periodico — vedi sensor.bilancia_listener_status su HA
# ---------------------------------------------------------------------------
_STATUS = {
    "phase": "init",
    "last_pesata_at": None,
    "last_pesata_user": None,
    "last_pesata_kg": None,
    "last_pesata_complete": None,
    "last_error_at": None,
    "last_error_msg": None,
    "scale_in_advertising": False,
    "rssi": None,
}


def set_phase(phase: str) -> None:
    _STATUS["phase"] = phase


def record_pesata(user: str, weight_kg: float, is_complete: bool) -> None:
    _STATUS["last_pesata_at"] = _dt.now().isoformat(timespec="seconds")
    _STATUS["last_pesata_user"] = user
    _STATUS["last_pesata_kg"] = weight_kg
    _STATUS["last_pesata_complete"] = is_complete


def _status_publisher_thread():
    import time as _t
    import json as _j
    from .ha_push import publish_status
    heartbeat_path = os.path.join(_log_dir, "heartbeat.json")
    while True:
        try:
            snapshot = dict(_STATUS)
            a = _METRICS.get("total_connects_attempted", 0)
            s = _METRICS.get("total_connects_succeeded", 0)
            snapshot["total_attempts"] = a
            snapshot["total_success"] = s
            snapshot["success_rate"] = round(100 * s / a, 1) if a else None
            snapshot["consecutive_fail"] = _METRICS.get("consecutive_failed_sessions", 0)
            publish_status(snapshot)
        except Exception:
            pass
        # Heartbeat file: scritto SEMPRE (anche se publish_status fallisce),
        # serve al watchdog PowerShell per capire se il processo è zombie.
        # Path: %LOCALAPPDATA%/BilanciaMondeer/heartbeat.json
        try:
            with open(heartbeat_path, "w", encoding="utf-8") as f:
                _j.dump({
                    "ts": int(_t.time()),
                    "iso": _dt.now().isoformat(timespec="seconds"),
                    "phase": _STATUS.get("phase"),
                    "pid": os.getpid(),
                }, f)
        except Exception:
            pass
        _t.sleep(30)


_threading_for_log.Thread(
    target=_status_publisher_thread, daemon=True, name="status_pub"
).start()


def looks_like_scale(device: BLEDevice, adv: AdvertisementData) -> bool:
    name = (device.name or adv.local_name or "").lower()
    if any(h in name for h in NAME_HINTS):
        return True
    advertised = {u.lower() for u in (adv.service_uuids or [])}
    return SERVICE_UUID.lower() in advertised


def find_notify_char(client: BleakClient):
    for service in client.services:
        if service.uuid.lower() != SERVICE_UUID.lower():
            continue
        for char in service.characteristics:
            if "notify" in char.properties:
                return char
    return None


NOTIFY_COUNTER = [0]
SESSION = {"client": None, "write_chars": [], "binding_sent": False,
           "profiles_sent": False, "time_sent": False, "loop": None,
           "next_write_idx": 1}


async def send_ack(device_class: int, data_type: int) -> None:
    """Manda un ACK (cmd=5) sul canale dedicato ec05 (last write char)."""
    client = SESSION["client"]
    write_chars = SESSION["write_chars"]
    if client is None or not client.is_connected or not write_chars:
        return
    ack_char = write_chars[-1]
    pkt = build_ack_packet(device_class, data_type)
    use_wwr = "write-without-response" in ack_char.properties
    try:
        await client.write_gatt_char(ack_char.uuid, pkt, response=not use_wwr)
        log.info("ACK sent dev=%d data=%d on %s", device_class, data_type,
                 ack_char.uuid[:8])
    except Exception as e:
        log.warning("ACK failed: %r", e)


async def send_time_sync() -> None:
    payload = build_ab_payload()
    log.info(">>> sending time sync (cmd=2 data=103, payload=%s)", payload.hex())
    await send_frame(3, 2, 103, payload, chunk_struct_size=8)


async def send_family_profiles() -> None:
    """Invia i 3 profili famiglia in un unico frame cmd=2 data=102."""
    refresh_profiles_from_ha()
    payload = b""
    for p in FAMILY_PROFILES:
        ae = build_ae_payload(
            class_type=1 if p["is_admin"] else 0,
            member_type=p["user_id"],
            user_id=p["user_id"],
            weight_low_kg=p["wmin"],
            weight_high_kg=p["wmax"],
            sex=p["sex"], age=p["age"], height_cm=p["height"],
        )
        payload += ae

    family_bytes = bytes([
        FAMILY_ID & 0xFF, (FAMILY_ID >> 8) & 0xFF,
        (FAMILY_ID >> 16) & 0xFF, (FAMILY_ID >> 24) & 0xFF,
    ])
    user_data = family_bytes + b"\x00" * 4

    log.info(">>> sending %d family profiles (cmd=2 data=102, %dB)",
             len(FAMILY_PROFILES), len(payload))
    await send_frame(3, 2, 102, payload, chunk_struct_size=16,
                     user_data=user_data)


async def send_frame(device_class: int, cmd_type: int, data_type: int,
                     payload: bytes, chunk_struct_size: int = 0,
                     user_data: bytes = b"") -> None:
    client = SESSION["client"]
    write_chars = SESSION["write_chars"]
    if client is None or not client.is_connected:
        log.warning("send_frame: client not connected")
        return
    pkts = build_packets(device_class, cmd_type, data_type, payload,
                         chunk_struct_size=chunk_struct_size,
                         user_data=user_data)
    for p in pkts:
        idx = SESSION["next_write_idx"]
        SESSION["next_write_idx"] = idx + 1
        if SESSION["next_write_idx"] >= len(write_chars) - 1:
            SESSION["next_write_idx"] = 1
        target = write_chars[idx]
        # Privilegia write-without-response (no ack per ogni TX): l'handshake
        # deve completare in <10s perché la bilancia si spegne se nessuno
        # sta sopra. Con response=True ogni TX aspetta 1-2s di ack.
        use_wwr = "write-without-response" in target.properties
        log.info("TX dev=%d cmd=%d data=%d packet on %s (wwr=%s): %s",
                 device_class, cmd_type, data_type,
                 target.uuid[:8], use_wwr, p.hex())
        try:
            await client.write_gatt_char(target.uuid, p, response=not use_wwr)
        except Exception as e:
            log.error("TX failed: %r", e)


def on_logical_frame(frame: LogicalFrame) -> None:
    log.info("FRAME dev=%d cmd=%d data=%d payload(%dB)=%s",
             frame.device_class, frame.cmd_type, frame.data_type,
             len(frame.payload), frame.payload.hex())

    loop = SESSION["loop"]

    if frame.device_class == 3 and frame.cmd_type == 2:
        if loop is not None:
            asyncio.run_coroutine_threadsafe(
                send_ack(frame.device_class, frame.data_type), loop)

    if frame.device_class == 3 and frame.cmd_type == 2 and frame.data_type == 2:
        if not SESSION["binding_sent"]:
            SESSION["binding_sent"] = True
            log.info(">>> received device info, sending binding match (cmd=2 data=104)")
            # Da c.java::m():
            #   if (i.a() == null || i.a().q()):  i2=1 i3=0 → ad(0, 1, ...)
            #   else:                             i3=2 i2=0 → ad(2, 0, ...)
            # ad costruttore = ad(type, subtype, family_id, last_id).
            # Listener stateless: usiamo il primo caso (type=0, subtype=1).
            ad = build_ad_payload(type_=0, subtype=1, family_id=FAMILY_ID, last_id=0)
            if loop is not None:
                asyncio.run_coroutine_threadsafe(
                    send_frame(3, 2, 104, ad, chunk_struct_size=8), loop)

    # Ordine ufficiale (da c.java::a(c cVar) line 601-605 + line 736-744):
    #   ack(104) → invio time sync (data=103) — c.java line 603 d().b(ab)
    #   recv data=3 → invio family profiles (data=102) — c.java line 740
    # NOTA: data=3 arriva DALLA bilancia subito dopo 104 ACK (è il "settings
    # sync" col valore N della bilancia). L'app ufficiale ignora 103 ACK.
    if frame.device_class == 3 and frame.cmd_type == 5 and frame.data_type == 104:
        if not SESSION["time_sent"]:
            SESSION["time_sent"] = True
            if loop is not None:
                asyncio.run_coroutine_threadsafe(send_time_sync(), loop)

    if frame.device_class == 3 and frame.cmd_type == 2 and frame.data_type == 3:
        if not SESSION["profiles_sent"]:
            SESSION["profiles_sent"] = True
            log.info(">>> received settings-sync (data=3), sending family profiles")
            if loop is not None:
                asyncio.run_coroutine_threadsafe(send_family_profiles(), loop)

    if frame.device_class == 3 and frame.data_type == 4:
        records = parse_all_weight_records(frame.payload)
        log.info("data=4 contains %d records", len(records))
        valid = [r for r in records if r.weight_kg and r.weight_kg > 5.0]
        if valid:
            best = max(valid, key=lambda r: r.timestamp_unix)
            log.info("RECORD %s 1kg fat=%s water=%s bone=%s muscle=%s "
                     "visc=%s bmi=%s (sex=%d age=%d h=%d ts=%d)",
                     "complete" if best.fat_pct is not None else "preliminary",
                     best.weight_kg, best.fat_pct, best.water_pct,
                     best.bone_kg, best.muscle_kg, best.visceral_fat,
                     best.bmi, best.sex, best.age, best.height_cm,
                     best.timestamp_unix)
            # Reverse-engineer: l'app ufficiale (c.java::a(h hVar) →
            # caso `f()==1 && e()==0 && c()<=0`) chiama `o()` che RIMANDA
            # i profili familiari quando arriva un frame anonimo (user_id=0,
            # age=0, owner_type=1). Senza questo step la bilancia non
            # appoggia i profili e non calcola la BIA per la pesata in
            # corso. NB: scattiamo sul PRIMO frame anonimo, indipendentemente
            # dal flag (0=finale, 2=real-time): la bilancia inizia a mandare
            # flag=2 per primi e dobbiamo "armare" i profili appena possibile,
            # altrimenti il primo frame finale arriva senza BIA.
            if (best.type == 1
                    and best.user_id == 0
                    and best.age == 0):
                if SESSION.get("anonymous_resend_done") is not True:
                    SESSION["anonymous_resend_done"] = True
                    log.info(">>> weight frame anonymous (flag=%d, user_id=0, "
                             "age=0): resend family profiles (mimicks "
                             "official app::o() recovery)", best.flag)
                    if loop is not None:
                        asyncio.run_coroutine_threadsafe(
                            send_family_profiles(), loop)
            try:
                _handle_weight_record(best)
            except Exception as e:
                log.error("weight handling failed: %r", e)


def _do_push(who: str, weight) -> None:
    if push_weight(who, weight):
        body_comp = None
        if weight.fat_pct is not None:
            body_comp = {
                "fat_percent": weight.fat_pct,
                "water_percent": weight.water_pct,
                "bone_kg": weight.bone_kg,
                "muscle_kg": weight.muscle_kg,
                "visceral_fat": weight.visceral_fat,
                "calorie_kcal": weight.calorie_kcal,
                "bmi": weight.bmi,
            }
        mark_pushed(who, weight.timestamp_unix, body_comp=body_comp)
        record_pesata(who, weight.weight_kg, weight.fat_pct is not None)


def _users_at_home(known_users: list) -> list:
    """Restituisce il sottoinsieme di known_users la cui ``person.<name>``
    è 'home' su HA. Se la chiamata fallisce, ritorna la lista completa
    (best-effort, non vogliamo bloccare la pesata).

    Usiamo l'entità ``person.<slug>`` (non device_tracker) perché aggrega
    tutti i tracker dell'utente (telefono, watch, GPS, BT proximity).
    """
    from .ha_push import HA_BASE_URL, HA_TOKEN
    if not HA_BASE_URL or not HA_TOKEN:
        return list(known_users)
    import urllib.request
    import json as _j
    out = []
    for name in known_users:
        try:
            url = f"{HA_BASE_URL}/api/states/person.{name}"
            req = urllib.request.Request(
                url, headers={"Authorization": f"Bearer {HA_TOKEN}"}
            )
            with urllib.request.urlopen(req, timeout=2.0) as r:
                state = _j.loads(r.read()).get("state", "")
            if state == "home":
                out.append(name)
            else:
                log.info("presence: %s is %r → excluded", name, state)
        except Exception as e:
            log.warning("presence check %s failed: %r — including conservatively",
                        name, e)
            out.append(name)
    return out


def _record_to_dict(weight) -> dict:
    """Convert WeightFrame to a flat dict the classifier understands."""
    return {
        "weight_kg": weight.weight_kg,
        "fat_pct": weight.fat_pct,
        "water_pct": weight.water_pct,
        "bone_kg": weight.bone_kg,
        "muscle_kg": weight.muscle_kg,
        "visceral_fat": weight.visceral_fat,
        "bmi": weight.bmi,
    }


def _handle_weight_record(weight) -> None:
    """Decide what to do with a fresh weight record:

    1. If it's a preliminary (BIA missing): use the legacy weight-range match.
       Cannot run the classifier on it (insufficient features).
    2. If it's a complete record (BIA present): run the classifier.
       - decision='auto'  → push directly under the predicted profile
       - decision='ask'   → publish an MQTT inquiry, HA sends a Telegram
                             keyboard, the user's reply lands as a callback
                             and the listener finalises the push.

    Anti-duplicate: a (user, timestamp) pair already pushed is skipped.
    """
    # ----- preliminary record -------------------------------------------
    if weight.fat_pct is None:
        who = match_profile(weight)
        if who.startswith("unknown"):
            log.warning("preliminary record: profile not matched, skipping push")
            return
        if already_pushed(who, weight.timestamp_unix):
            log.info("preliminary already pushed for %s ts=%d",
                     who, weight.timestamp_unix)
            return
        log.info("preliminary record matched by weight range to %s, pushing", who)
        _do_push(who, weight)
        return

    # ----- complete record ----------------------------------------------
    record_dict = _record_to_dict(weight)
    all_users = [p["name"] for p in FAMILY_PROFILES]

    # Presence filter: chi non è a casa non può essersi pesato. Se la
    # presenza identifica un singolo candidato, salta classifier+Telegram.
    at_home = _users_at_home(all_users)
    log.info("presence filter: at_home=%s (out of %s)", at_home, all_users)

    if len(at_home) == 1:
        who = at_home[0]
        log.info("PRESENCE-only auto: only %s is home, attributing weighing", who)
        if already_pushed(who, weight.timestamp_unix):
            log.info("complete already pushed for %s ts=%d",
                     who, weight.timestamp_unix)
            return
        _do_push(who, weight)
        # Sample comunque salvato: arricchisce il classifier per il futuro
        # (quando ci saranno più persone in casa contemporaneamente).
        try:
            save_sample(who, record_dict)
        except Exception as e:
            log.warning("save_sample failed: %r", e)
        return

    # 0 a casa → strano, magari device_tracker confuso o il listener stesso
    # vede come not_home; classifichiamo sull'intera famiglia, conservativi.
    candidates = at_home if at_home else all_users
    if len(at_home) == 0:
        log.warning("nobody at home according to person.* — falling back to full classifier")

    result = classify(record_dict, candidates)
    log.info("CLASSIFIER: %s", result.reason)

    if result.decision == "auto":
        who = result.predicted_user
        if already_pushed(who, weight.timestamp_unix):
            log.info("complete already pushed for %s ts=%d",
                     who, weight.timestamp_unix)
            return
        log.info("AUTO classification → %s (conf=%.2f)", who, result.confidence)
        _do_push(who, weight)
        # Auto-classified samples reinforce the centroids over time. Store
        # them too — gives the classifier more data without bothering the user.
        try:
            save_sample(who, record_dict)
        except Exception as e:
            log.warning("save_sample failed: %r", e)
        return

    # decision == "ask" → publish MQTT inquiry, HA will Telegram-prompt
    inquiry_id = f"{int(weight.timestamp_unix)}-{int(weight.weight_kg * 10)}"
    # De-dupe: la bilancia spesso manda 2-3 weight frame complete consecutivi
    # a 1-2s di distanza per la stessa pesata. Se mandiamo 1 ASK Telegram
    # per ciascuno, l'utente riceve N notifiche identiche e (se conferma
    # tutte) abbiamo N publish e quindi N notifiche di "Pesata pronta".
    # Skipiamo se abbiamo già fatto ASK negli ultimi 60s di clock-bilancia.
    last_ask_ts = SESSION.get("last_ask_ts", 0)
    if abs(int(weight.timestamp_unix) - last_ask_ts) < 60:
        log.info("ASK skipped for ts=%d (already asked at ts=%d, within 60s window)",
                 weight.timestamp_unix, last_ask_ts)
        return
    SESSION["last_ask_ts"] = int(weight.timestamp_unix)
    log.info("ASK via Telegram, inquiry_id=%s", inquiry_id)

    # Capture state needed by the MQTT-thread callback (no closure on `weight`
    # alone — store the dict too, so we can re-train).
    def on_answer(answer: dict) -> None:
        name = (answer or {}).get("name")
        if not name:
            log.warning("inquiry %s answered with no name: %r", inquiry_id, answer)
            return
        # Sanity check: validato sull'INTERA famiglia, non solo at_home.
        # Caso d'uso: User2 è uscita senza il telefono → presence dice
        # not_home ma è davvero a casa. L'utente conferma via Telegram e
        # vogliamo accettarlo.
        if name not in all_users:
            log.warning("inquiry %s: answer %r is not a known user, ignoring",
                        inquiry_id, name)
            return
        if already_pushed(name, weight.timestamp_unix):
            log.info("complete already pushed for %s ts=%d (after Telegram)",
                     name, weight.timestamp_unix)
            return
        log.info("Telegram → %s confirmed for ts=%d, pushing + training",
                 name, weight.timestamp_unix)
        try:
            _do_push(name, weight)
            save_sample(name, record_dict)
        except Exception as e:
            log.error("post-Telegram push failed: %r", e)

    publish_inquiry(
        inquiry_id=inquiry_id,
        record_dict=record_dict,
        predicted=result.predicted_user,
        confidence=result.confidence,
        reason=result.reason,
        callback=on_answer,
    )

    # Timeout: if no Telegram answer in 5 minutes, drop the pending callback
    # so memory does not leak. The next weighing of the same user can still
    # be classified later (we did not push, so anti-duplicate won't block it).
    def _timeout_drop():
        cancel_inquiry(inquiry_id)
        log.warning("inquiry %s timed out, dropped pending callback", inquiry_id)

    threading.Timer(300.0, _timeout_drop).start()


REASSEMBLER = FrameReassembler(on_logical_frame)


def on_notify(sender, data: bytearray) -> None:
    NOTIFY_COUNTER[0] += 1
    payload = bytes(data)
    pkt = parse_ble_packet(payload)
    if pkt is None:
        log.warning("notify too short: %s", payload.hex())
        return
    log.info("pkt dev=%d cmd=%d data=%d total=%d idx=%d chunk=%s",
             pkt.device_class, pkt.cmd_type, pkt.data_type,
             pkt.total_packets, pkt.packet_index, pkt.payload16.hex())
    REASSEMBLER.feed(pkt)


async def _try_connect(device: BLEDevice) -> Optional[BleakClient]:
    """Tenta il connect con backoff intelligente + pre-pair al 3° tentativo.

    - Tentativi 1-2: rapidi (4s timeout, 0.3s backoff). Spesso bastano se
      Windows ha già il bond memorizzato.
    - Tentativo 3: prima del connect prova `pair_async`. Forza Windows a
      registrare il bond ora (utile alla prima sessione assoluta col chip
      o dopo che il bond è "andato in cache miss").
    - Tentativi 4-6: backoff più lungo (1.5s) — magari il chip sta ancora
      processando la sessione precedente.
    """
    t_session_start = asyncio.get_running_loop().time()
    for attempt in range(1, CONNECT_MAX_ATTEMPTS + 1):
        client = BleakClient(device, timeout=CONNECT_TIMEOUT_S)
        t_start = asyncio.get_running_loop().time()
        try:
            if attempt == 3:
                # Pre-pairing best-effort. La WanKa C1 non richiede pairing
                # classico, ma Windows ne approfitta per cachare gli handle.
                try:
                    await asyncio.wait_for(client.pair(), timeout=3.0)
                    log.info("pre-pair OK at attempt %d", attempt)
                except Exception as ep:
                    log.debug("pre-pair skipped: %s", type(ep).__name__)
            await client.connect()
            elapsed = asyncio.get_running_loop().time() - t_start
            session_dur = asyncio.get_running_loop().time() - t_session_start
            log.info("connect OK at attempt %d in %.1fs", attempt, elapsed)
            _metrics_record_session(success=True, attempts=attempt,
                                    duration_s=session_dur)
            return client
        except Exception as e:
            elapsed = asyncio.get_running_loop().time() - t_start
            log.warning("connect attempt %d failed in %.1fs: %s",
                        attempt, elapsed, type(e).__name__)
            try:
                await client.disconnect()
            except Exception:
                pass
            await asyncio.sleep(1.5 if attempt >= 3 else 0.3)
    session_dur = asyncio.get_running_loop().time() - t_session_start
    _metrics_record_session(success=False, attempts=CONNECT_MAX_ATTEMPTS,
                            duration_s=session_dur)
    return None


async def handle_device(device: BLEDevice) -> None:
    log.info("connecting to %s (name=%r), max %d attempts",
             device.address, device.name, CONNECT_MAX_ATTEMPTS)
    set_phase("connecting")
    SESSION["binding_sent"] = False
    SESSION["profiles_sent"] = False
    SESSION["time_sent"] = False
    SESSION["anonymous_resend_done"] = False
    SESSION["last_ask_ts"] = 0
    SESSION["next_write_idx"] = 1
    SESSION["loop"] = asyncio.get_running_loop()
    client = await _try_connect(device)
    if client is None:
        log.error("all connect attempts failed, back to scan")
        set_phase("scanning")
        return
    set_phase("handshake")
    try:
        SESSION["client"] = client
        services = list(client.services)
        log.info("connected, services discovered: %d", len(services))
        notify_chars = []
        for s in services:
            for c in s.characteristics:
                if "notify" in c.properties or "indicate" in c.properties:
                    notify_chars.append(c)

        if not notify_chars:
            log.warning("no notify/indicate characteristics found")
            return

        for c in notify_chars:
            try:
                await client.start_notify(c.uuid, on_notify)
            except Exception as e:
                log.warning("failed to subscribe %s: %r", c.uuid, e)

        write_chars = []
        for s in services:
            if s.uuid.lower() != SERVICE_UUID.lower():
                continue
            for c in s.characteristics:
                if "write" in c.properties or "write-without-response" in c.properties:
                    write_chars.append(c)
        SESSION["write_chars"] = write_chars
        target = write_chars[1] if len(write_chars) > 1 else write_chars[0]
        use_wwr = "write-without-response" in target.properties
        try:
            await client.write_gatt_char(target.uuid, WAKE_PACKET,
                                         response=not use_wwr)
            SESSION["next_write_idx"] = 2
            log.info("wake packet sent on %s", target.uuid[:8])
        except Exception as e:
            log.error("wake failed: %r", e)

        # Idle timeout: 90s. La BIA può tardare 10-20s dopo il primo weight
        # frame; con timeout 30s spesso la perdiamo. 90s è abbondante e non
        # dà fastidio (la bilancia si stacca da sola appena scendi).
        log.info("listening, idle timeout 90s")
        idle_for = 0.0
        last_count = 0
        while client.is_connected and idle_for < 90.0:
            await asyncio.sleep(0.5)
            idle_for += 0.5
            if NOTIFY_COUNTER[0] != last_count:
                last_count = NOTIFY_COUNTER[0]
                idle_for = 0.0
        if not client.is_connected:
            log.info("scale disconnected (powered off)")
        else:
            log.info("idle 90s without data, releasing connection")
    except Exception as exc:
        log.error("session error: %s: %r", type(exc).__name__, exc)
    finally:
        try:
            if client.is_connected:
                await client.disconnect()
        except Exception:
            pass
        SESSION["client"] = None


async def _mqtt_health_loop() -> None:
    """Health-check periodico del client MQTT.

    Sintomo riscontrato: dopo qualche minuto di idle, paho-mqtt smette di
    mandare PINGREQ e il broker disconnette per timeout. Il publish
    successivo trova `is_connected()=False` e ricrea il client (OK), ma
    nel frattempo la SUBSCRIBE è morta — l'utente clicca Telegram, HA
    pubblica l'answer, ma il listener non lo riceve.

    NB: `_get_mqtt()` è SYNC e contiene `client.connect()` che blocca su
    TCP fino a 60s+ se il broker non risponde. Lo eseguiamo via
    `asyncio.to_thread()` per non bloccare il loop principale (BLE
    scanner) mentre attendiamo la riconnessione MQTT.
    """
    from .ha_push import _get_mqtt
    iteration = 0
    while True:
        iteration += 1
        try:
            log.debug("[health] iter=%d calling _get_mqtt() in thread", iteration)
            client = await asyncio.to_thread(_get_mqtt)
            connected = client.is_connected()
            if iteration % 10 == 0 or not connected:
                log.info("[health] iter=%d connected=%s", iteration, connected)
            if not connected:
                log.warning("[health] iter=%d client reports disconnected", iteration)
        except Exception as e:
            log.warning("[health] iter=%d FAILED: %r", iteration, e)
        await asyncio.sleep(30)


async def main() -> None:
    # Pre-warm MQTT prima di qualsiasi log.info, così quando attivo il MQTT
    # log handler il broker è già connesso e nessun publish blocca su connect.
    try:
        from .ha_push import _get_mqtt
        _get_mqtt()
    except Exception as e:
        log.warning("MQTT pre-warm failed: %r", e)
    _enable_mqtt_log_handler()
    # Avvia il task di health-check in background (non blocca main loop).
    asyncio.create_task(_mqtt_health_loop())
    refresh_profiles_from_ha()
    log.info("starting permanent BLE scan (waiting for scale to power on)")
    last_seen: dict[str, float] = {}
    queue: asyncio.Queue[BLEDevice] = asyncio.Queue()

    def on_detection(device: BLEDevice, adv: AdvertisementData) -> None:
        if not looks_like_scale(device, adv):
            log.debug("ignoring %s name=%r uuids=%s",
                      device.address, device.name, adv.service_uuids)
            return
        loop_now = asyncio.get_event_loop().time()
        if loop_now - last_seen.get(device.address, 0.0) < RECONNECT_COOLDOWN_S:
            return
        last_seen[device.address] = loop_now
        log.info("scale advertising: %s name=%r rssi=%d",
                 device.address, device.name, adv.rssi)
        _STATUS["scale_in_advertising"] = True
        _STATUS["rssi"] = adv.rssi
        queue.put_nowait(device)

    scanner = BleakScanner(detection_callback=on_detection)
    await scanner.start()
    try:
        iter_count = 0
        while True:
            iter_count += 1
            log.debug("[main] iter=%d waiting for queue.get()", iter_count)
            device = await queue.get()
            log.info("[main] iter=%d got device %s", iter_count, device.address)
            try:
                await scanner.stop()
                log.debug("[main] iter=%d scanner.stop() OK, calling handle_device",
                          iter_count)
            except Exception as e:
                log.warning("[main] iter=%d scanner.stop() failed: %r",
                            iter_count, e)
            try:
                await handle_device(device)
                log.debug("[main] iter=%d handle_device returned", iter_count)
            except Exception as e:
                log.error("[main] iter=%d handle_device EXCEPTION: %r",
                          iter_count, e, exc_info=True)
            finally:
                try:
                    await scanner.start()
                    log.info("[main] iter=%d resumed scan, waiting for next pesata",
                             iter_count)
                except Exception as e:
                    log.error("[main] iter=%d scanner.start() FAILED: %r",
                              iter_count, e, exc_info=True)
    finally:
        await scanner.stop()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        log.info("interrupted, exiting")
