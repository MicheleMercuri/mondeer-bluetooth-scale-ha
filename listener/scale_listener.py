"""Permanent BLE listener for Mondeer / WanKa C1 / CE-Link OEM scales.

The scale powers on for ~10 seconds when somebody steps on it, advertises a
``WanKa C1`` BLE peripheral with primary service UUID ``0xcc08``, and then
powers off automatically. To capture every weighing this listener keeps an
active scanner running in background and connects on-the-fly when it sees
the advertising packet.

High-level session flow once a connection is established:

  1. Send ``WAKE_PACKET`` on a write characteristic (round-robin on ec01..ec04).
  2. Receive ``device info`` (cmd=2, data=2)  → reply with binding match
     (cmd=2, data=104) carrying the configured ``family_id``.
  3. Scale ACKs the binding (cmd=5, data=104) → send the family profiles
     (cmd=2, data=102): one ``ae`` payload per user (16 bytes each).
  4. Scale ACKs profiles (cmd=5, data=102) → send time sync
     (cmd=2, data=103) so the scale's RTC matches the listener.
  5. Scale streams weight records (data=4). A "preliminary" record carries
     just the weight (BIA fields are 0xFFFF); a "complete" record adds
     fat / water / bone / muscle / visceral / kcal / BMI.

Run with ``python -m listener.scale_listener``. Stop with Ctrl+C.
"""
from __future__ import annotations

import asyncio
import json as _json_mod
import logging
import os
import subprocess as _sp
import sys
from datetime import datetime as _dt
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Optional

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData

from .config import Config, Profile, get_config  # noqa: F401
from .ha_push import already_pushed, mark_pushed, push_weight
from .parser import (
    FrameReassembler,
    LogicalFrame,
    build_ab_payload,
    build_ack_packet,
    build_ad_payload,
    build_ae_payload,
    build_packets,
    parse_all_weight_records,
    parse_ble_packet,
)


# ---------------------------------------------------------------------------
# Constants (BLE-level)
# ---------------------------------------------------------------------------

SERVICE_UUID = "0000cc08-0000-1000-8000-00805f9b34fb"
NAME_HINTS = ("mondeer", "scale", "cheng", "ce-link", "celink", "wanka")

RECONNECT_COOLDOWN_S = 1.5

# The scale stays on ~10 seconds. Short timeout + several attempts: when
# Windows is "ready" it connects in <1s, otherwise we cut early.
CONNECT_TIMEOUT_S = 4.0
CONNECT_MAX_ATTEMPTS = 6

# 20-byte wake packet: header [0x03,0x04,0x02,0x00] + zero payload. Sent on
# the first write characteristic right after connect to nudge the scale
# into emitting its device info frame.
WAKE_PACKET = bytes([
    0x03, 0x04, 0x02, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
])


# ---------------------------------------------------------------------------
# Logging + metrics setup
# ---------------------------------------------------------------------------

try:
    sys.stdout.reconfigure(line_buffering=True)
except Exception:
    pass

CFG: Config = get_config()
PROFILES: list[Profile] = list(CFG.profiles)

_state_dir = Path(CFG.state_dir).expanduser()
_state_dir.mkdir(parents=True, exist_ok=True)
_log_file = _state_dir / "listener.log"
_metrics_file = _state_dir / "metrics.json"

_root = logging.getLogger()
_root.setLevel(logging.INFO)
_root.handlers.clear()
_fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s")
_fh = RotatingFileHandler(_log_file, maxBytes=2_000_000, backupCount=3, encoding="utf-8")
_fh.setFormatter(_fmt)
_root.addHandler(_fh)
if sys.stdout and getattr(sys.stdout, "isatty", lambda: False)():
    _ch = logging.StreamHandler(sys.stdout)
    _ch.setFormatter(_fmt)
    _root.addHandler(_ch)

log = logging.getLogger("listener")


_METRICS = {
    "consecutive_failed_sessions": 0,
    "total_connects_attempted": 0,
    "total_connects_succeeded": 0,
    "total_disconnects": 0,
    "total_recoveries": 0,
    "last_failure_iso": None,
    "last_recovery_iso": None,
    "session_history": [],  # last 50 sessions
}


def _metrics_load() -> None:
    try:
        with _metrics_file.open("r", encoding="utf-8") as f:
            saved = _json_mod.load(f)
        _METRICS.update({k: v for k, v in saved.items() if k in _METRICS})
    except FileNotFoundError:
        pass
    except Exception as e:
        log.warning("metrics load failed: %r", e)


def _metrics_save() -> None:
    try:
        with _metrics_file.open("w", encoding="utf-8") as f:
            _json_mod.dump(_METRICS, f, indent=2)
    except Exception as e:
        log.warning("metrics save failed: %r", e)


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
# Auto-recovery (Windows only, requires admin)
# ---------------------------------------------------------------------------

async def _restart_bthserv() -> bool:
    """Restart Windows ``bthserv`` to clear a stuck Bluetooth radio.

    Some older BLE chipsets (e.g. Intel 7260) end up in an unrecoverable
    state after a few failed sessions; only a service restart (or a full
    reboot) brings them back. The listener triggers this automatically
    after ``recovery_after_n_failed_sessions`` consecutive failures, and
    only when ``enable_auto_recovery: true`` is set in the config.
    """
    if sys.platform != "win32":
        log.warning("auto-recovery is Windows-only, skipping")
        return False
    log.warning("AUTO-RECOVERY: restarting Windows Bluetooth service")
    try:
        result = await asyncio.to_thread(
            _sp.run,
            ["powershell", "-NoProfile", "-Command",
             "Restart-Service bthserv -Force -ErrorAction Stop"],
            capture_output=True, text=True, timeout=15,
        )
        if result.returncode == 0:
            log.info("AUTO-RECOVERY: bthserv restarted OK")
            _METRICS["total_recoveries"] += 1
            _METRICS["last_recovery_iso"] = _dt.now().isoformat(timespec="seconds")
            _METRICS["consecutive_failed_sessions"] = 0
            _metrics_save()
            await asyncio.sleep(5.0)  # let bthserv stabilise
            return True
        log.error("AUTO-RECOVERY: bthserv restart failed (rc=%d): %s",
                  result.returncode, result.stderr.strip())
        return False
    except Exception as e:
        log.error("AUTO-RECOVERY exception: %r", e)
        return False


# ---------------------------------------------------------------------------
# Profiles helpers
# ---------------------------------------------------------------------------

def refresh_profiles_from_ha() -> None:
    """Optionally re-read the family profiles from HA helper entities.

    The HA package ``home_assistant/packages/bilancia.yaml`` defines, for
    each profile name in the config, helpers like
    ``input_select.bilancia_<name>_sesso``,
    ``input_number.bilancia_<name>_eta`` etc. so the user can edit the
    family from a dashboard without restarting the listener. If the
    helpers cannot be read, the values from ``config.yaml`` are kept.
    """
    if not CFG.ha_base_url or not CFG.ha_token:
        return
    import urllib.request

    def _state(entity_id):
        url = f"{CFG.ha_base_url}/api/states/{entity_id}"
        req = urllib.request.Request(
            url, headers={"Authorization": f"Bearer {CFG.ha_token}"}
        )
        with urllib.request.urlopen(req, timeout=3.0) as r:
            return _json_mod.loads(r.read())["state"]

    for p in PROFILES:
        try:
            sex_str = _state(f"input_select.bilancia_{p.name}_sesso")
            p.sex = 1 if sex_str.upper() == "M" else 0
            p.age = int(float(_state(f"input_number.bilancia_{p.name}_eta")))
            p.height_cm = int(float(_state(f"input_number.bilancia_{p.name}_altezza")))
            p.weight_min = float(_state(f"input_number.bilancia_{p.name}_peso_min"))
            p.weight_max = float(_state(f"input_number.bilancia_{p.name}_peso_max"))
            log.info("profile %s loaded from HA: sex=%d age=%d h=%d w=%g-%g",
                     p.name, p.sex, p.age, p.height_cm, p.weight_min, p.weight_max)
        except Exception as e:
            log.warning("profile %s: HA fetch failed (%s), using config defaults",
                        p.name, e)


def match_profile(record) -> str:
    """Match a weight record to a configured profile.

    First, exact match on ``(sex, age, height_cm)`` (these are echoed back
    by the scale on complete records once profiles have been registered).
    For preliminary records the scale sends ``sex=age=height=0``: in that
    case we fall back to weight-range matching, which only works if the
    family ranges do not overlap. An ambiguous match returns ``unknown``
    and the record is dropped.
    """
    for p in PROFILES:
        if (record.sex == p.sex
                and record.age == p.age
                and record.height_cm == p.height_cm):
            return p.name
    if record.sex == 0 and record.age == 0 and record.height_cm == 0:
        candidates = [p for p in PROFILES
                      if p.weight_min <= record.weight_kg <= p.weight_max]
        if len(candidates) == 1:
            log.info("match by weight range only: %s (%.1fkg in [%g,%g])",
                     candidates[0].name, record.weight_kg,
                     candidates[0].weight_min, candidates[0].weight_max)
            return candidates[0].name
        if len(candidates) > 1:
            log.warning("ambiguous weight match for %.1fkg: %s",
                        record.weight_kg, [c.name for c in candidates])
    return f"unknown(sex={record.sex},age={record.age},h={record.height_cm})"


# ---------------------------------------------------------------------------
# Session state
# ---------------------------------------------------------------------------

NOTIFY_COUNTER = [0]
SESSION = {
    "client": None,
    "write_chars": [],
    "binding_sent": False,
    "profiles_sent": False,
    "time_sent": False,
    "loop": None,
    "next_write_idx": 1,
}


def looks_like_scale(device: BLEDevice, adv: AdvertisementData) -> bool:
    name = (device.name or adv.local_name or "").lower()
    if any(h in name for h in NAME_HINTS):
        return True
    advertised = {u.lower() for u in (adv.service_uuids or [])}
    return SERVICE_UUID.lower() in advertised


# ---------------------------------------------------------------------------
# Outbound frame helpers
# ---------------------------------------------------------------------------

async def send_ack(device_class: int, data_type: int) -> None:
    """Send a cmd=5 ACK on the dedicated ``ec05`` (last write) characteristic."""
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
    """Send all configured profiles in a single cmd=2 data=102 frame."""
    refresh_profiles_from_ha()
    payload = b""
    for p in PROFILES:
        ae = build_ae_payload(
            class_type=1 if p.is_admin else 0,
            member_type=p.user_id,
            user_id=p.user_id,
            weight_low_kg=p.weight_min,
            weight_high_kg=p.weight_max,
            sex=p.sex, age=p.age, height_cm=p.height_cm,
        )
        payload += ae

    fid = CFG.family_id
    family_bytes = bytes([
        fid & 0xFF, (fid >> 8) & 0xFF,
        (fid >> 16) & 0xFF, (fid >> 24) & 0xFF,
    ])
    user_data = family_bytes + b"\x00" * 4

    log.info(">>> sending %d profiles (cmd=2 data=102, %dB)",
             len(PROFILES), len(payload))
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
        # Prefer write-without-response: the full handshake must complete
        # within ~10 s before the scale powers off, so we cannot afford to
        # wait for an ACK on every packet.
        use_wwr = "write-without-response" in target.properties
        log.info("TX dev=%d cmd=%d data=%d packet on %s (wwr=%s): %s",
                 device_class, cmd_type, data_type,
                 target.uuid[:8], use_wwr, p.hex())
        try:
            await client.write_gatt_char(target.uuid, p, response=not use_wwr)
        except Exception as e:
            log.error("TX failed: %r", e)


# ---------------------------------------------------------------------------
# Notify callback / frame reassembly
# ---------------------------------------------------------------------------

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
            ad = build_ad_payload(
                type_=0, subtype=1, family_id=CFG.family_id, last_id=0,
            )
            if loop is not None:
                asyncio.run_coroutine_threadsafe(
                    send_frame(3, 2, 104, ad, chunk_struct_size=8), loop)

    if frame.device_class == 3 and frame.cmd_type == 5 and frame.data_type == 104:
        if not SESSION["profiles_sent"]:
            SESSION["profiles_sent"] = True
            if loop is not None:
                asyncio.run_coroutine_threadsafe(send_family_profiles(), loop)

    if frame.device_class == 3 and frame.cmd_type == 5 and frame.data_type == 102:
        if not SESSION["time_sent"]:
            SESSION["time_sent"] = True
            if loop is not None:
                asyncio.run_coroutine_threadsafe(send_time_sync(), loop)

    if frame.device_class == 3 and frame.data_type == 4:
        records = parse_all_weight_records(frame.payload)
        log.info("data=4 contains %d records", len(records))
        # Accept records with a valid weight even if BIA is missing: a
        # preliminary record is still useful (the dashboard merges old
        # body comp via ha_push.get_last_body_comp).
        valid = [r for r in records if r.weight_kg and r.weight_kg > 5.0]
        if valid:
            best = max(valid, key=lambda r: r.timestamp_unix)
            who = match_profile(best)
            log.info("VALID WEIGHT %s %.1fkg fat=%s water=%s "
                     "bone=%s muscle=%s visc=%s cal=%s bmi=%s "
                     "(sex=%d age=%d h=%d ts=%d)",
                     who, best.weight_kg, best.fat_pct, best.water_pct,
                     best.bone_kg, best.muscle_kg, best.visceral_fat,
                     best.calorie_kcal, best.bmi, best.sex, best.age,
                     best.height_cm, best.timestamp_unix)
            if who.startswith("unknown"):
                log.warning("profile not matched, skipping push")
            elif already_pushed(who, best.timestamp_unix):
                log.info("already pushed for %s ts=%d", who, best.timestamp_unix)
            else:
                # Synchronous push from the notify thread: the BLE radio may
                # drop the connection right after data=4 arrives; pushing
                # asynchronously could lose the reading if the event loop
                # dies before the publish completes. Blocking ~hundred ms
                # here is acceptable.
                try:
                    _do_push(who, best)
                except Exception as e:
                    log.error("sync push failed: %r", e)


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


# ---------------------------------------------------------------------------
# Connection management
# ---------------------------------------------------------------------------

async def _try_connect(device: BLEDevice) -> Optional[BleakClient]:
    """Connect with intelligent backoff.

    - Attempts 1-3: fast (4 s timeout, 0.3 s pause). The scale sometimes
      needs one or two failed attempts before Windows clears the previous
      session state.
    - Attempt 4: best-effort ``pair_async`` before connect. On Windows
      this can shortcut the "transparent pairing" delay (30 s → ~2 s).
    - Attempts 5-6: longer pause (1.5 s) — give the chip more time.
    """
    t_session_start = asyncio.get_running_loop().time()
    for attempt in range(1, CONNECT_MAX_ATTEMPTS + 1):
        client = BleakClient(device, timeout=CONNECT_TIMEOUT_S)
        t_start = asyncio.get_running_loop().time()
        try:
            if attempt == 4:
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
            await asyncio.sleep(1.5 if attempt >= 4 else 0.3)
    session_dur = asyncio.get_running_loop().time() - t_session_start
    _metrics_record_session(success=False, attempts=CONNECT_MAX_ATTEMPTS,
                            duration_s=session_dur)
    return None


async def handle_device(device: BLEDevice) -> None:
    log.info("connecting to %s (name=%r), max %d attempts",
             device.address, device.name, CONNECT_MAX_ATTEMPTS)
    SESSION["binding_sent"] = False
    SESSION["profiles_sent"] = False
    SESSION["time_sent"] = False
    SESSION["next_write_idx"] = 1
    SESSION["loop"] = asyncio.get_running_loop()
    client = await _try_connect(device)
    if client is None:
        log.error("all connect attempts failed, back to scan")
        return
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

        log.info("listening, idle timeout 30s")
        idle_for = 0.0
        last_count = 0
        while client.is_connected and idle_for < 30.0:
            await asyncio.sleep(0.5)
            idle_for += 0.5
            if NOTIFY_COUNTER[0] != last_count:
                last_count = NOTIFY_COUNTER[0]
                idle_for = 0.0
        if not client.is_connected:
            log.info("scale disconnected (powered off)")
        else:
            log.info("idle 30s without data, releasing connection")
    except Exception as exc:
        log.error("session error: %s: %r", type(exc).__name__, exc)
    finally:
        try:
            if client.is_connected:
                await client.disconnect()
        except Exception:
            pass
        SESSION["client"] = None


# ---------------------------------------------------------------------------
# Main loop
# ---------------------------------------------------------------------------

async def main() -> None:
    if not PROFILES:
        log.error("no profiles configured. Edit config.yaml and add at "
                  "least one profile under 'profiles:'.")
        return
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
        queue.put_nowait(device)

    scanner = BleakScanner(detection_callback=on_detection)
    await scanner.start()
    try:
        while True:
            device = await queue.get()
            await scanner.stop()
            try:
                await handle_device(device)
            finally:
                # Auto-recovery: if too many sessions failed in a row the BT
                # radio is stuck. Restart bthserv before resuming the scan.
                if (CFG.enable_auto_recovery
                        and _METRICS["consecutive_failed_sessions"]
                        >= CFG.recovery_after_n_failed_sessions):
                    log.warning(
                        "%d consecutive failed sessions, triggering recovery",
                        _METRICS["consecutive_failed_sessions"])
                    await _restart_bthserv()
                    try:
                        await scanner.stop()
                    except Exception:
                        pass
                    scanner = BleakScanner(detection_callback=on_detection)
                await scanner.start()
                log.info("resumed scan, waiting for next pesata")
    finally:
        await scanner.stop()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        log.info("interrupted, exiting")
