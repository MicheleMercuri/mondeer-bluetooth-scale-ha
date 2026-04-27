"""Parser pacchetti bilancia Mondeer / CE-Link (service 0xcc08).

Due livelli:
1. BLE packet 20 byte: header 4B + chunk 16B. Riassemblati in frame logici.
2. Frame logico: device_class, cmd_type, data_type, payload riassemblato.
   - data_type=2 → device info (32 byte di nome modello, ecc.)
   - data_type=4 → weight + body composition (32 byte, layout c/m.java)
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Optional, Callable


SENTINEL_U16 = 0xFFFF
SENTINEL_U8 = 0xFF
LB_TO_KG = 0.45359237


def crc16_celink(data: bytes) -> int:
    """CRC-16 polinomio 0x8005, init 0, no reflection. Da bluetooth/a/c.java."""
    crc = 0
    for b in data:
        crc = ((b << 8) ^ crc) & 0xFFFF
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x8005) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


def build_packets(device_class: int, cmd_type: int, data_type: int,
                  payload: bytes, user_id: int = 0,
                  chunk_struct_size: int = 0,
                  user_data: bytes = b"") -> list[bytes]:
    """Costruisce la lista di pacchetti BLE 20-byte per inviare un frame logico.

    Args:
        device_class: 1=wristband, 3=scale
        cmd_type: 2=data, 4=request, 5=ACK
        data_type: opcode specifico (104=binding match, 102=user info, ...)
        payload: dati del frame logico (verrà splittato in chunk da 16 byte)
        user_id: id utente da mettere nell'header (offset 8-11)
        chunk_struct_size: dimensione di un singolo "record" nel payload
            (per data=104 → ad.b()=8, per 102 → ae.b()=16). Mette nel byte[5] dell'header.
        user_data: 8 byte custom da mettere in coda all'header
    """
    chunks = []
    for i in range(0, len(payload), 16):
        c = payload[i:i + 16]
        if len(c) < 16:
            c = c + b"\x00" * (16 - len(c))
        chunks.append(c)

    n_chunks = len(chunks)
    crc = crc16_celink(payload) if payload else 0
    if chunk_struct_size > 0 and len(payload) > 0:
        n_records = (len(payload) // chunk_struct_size) + (
            1 if len(payload) % chunk_struct_size else 0)
    else:
        n_records = 0

    user_data_padded = user_data + b"\x00" * (8 - len(user_data))
    user_data_padded = user_data_padded[:8]

    header_payload = bytes([
        n_records & 0xFF,
        chunk_struct_size & 0xFF,
        crc & 0xFF, (crc >> 8) & 0xFF,
        user_id & 0xFF, (user_id >> 8) & 0xFF,
        (user_id >> 16) & 0xFF, (user_id >> 24) & 0xFF,
    ]) + user_data_padded

    seq_byte = ((n_chunks & 0x0F) << 4) | 0
    out = [bytes([device_class, cmd_type, data_type, seq_byte]) + header_payload]

    for idx, chunk in enumerate(chunks, start=1):
        seq_byte = ((n_chunks & 0x0F) << 4) | (idx & 0x0F)
        out.append(bytes([device_class, cmd_type, data_type, seq_byte]) + chunk)

    return out


def build_ad_payload(type_: int, subtype: int, family_id: int, last_id: int = 0) -> bytes:
    """Payload del binding match (cmd=2 data=104). Da c/ad.java."""
    return bytes([
        type_ & 0xFF,
        subtype & 0xFF,
        family_id & 0xFF, (family_id >> 8) & 0xFF,
        (family_id >> 16) & 0xFF, (family_id >> 24) & 0xFF,
        last_id & 0xFF, (last_id >> 8) & 0xFF,
    ])


def build_ab_payload(now=None) -> bytes:
    """Time sync payload (8 byte). Da c/ab.java."""
    from datetime import datetime
    if now is None:
        now = datetime.now()
    weekday = (now.weekday() + 1) % 7
    return bytes([
        (now.year - 2000) & 0xFF,
        now.month & 0xFF,
        now.day & 0xFF,
        weekday & 0xFF,
        now.hour & 0xFF,
        now.minute & 0xFF,
        now.second & 0xFF,
        0x01,
    ])


def build_ack_packet(device_class: int, data_type: int) -> bytes:
    """Pacchetto ACK 20 byte: cmd=5, payload zero."""
    return bytes([
        device_class & 0xFF,
        0x05,
        data_type & 0xFF,
        0x00,
    ]) + b"\x00" * 16


def build_ae_payload(class_type: int, member_type: int, user_id: int,
                     weight_low_kg: float, weight_high_kg: float,
                     sex: int, age: int, height_cm: int) -> bytes:
    """Profilo utente bilancia (16 byte). Layout c/ae.java::a()."""
    wl = int(weight_low_kg * 10) & 0xFFFF
    wh = int(weight_high_kg * 10) & 0xFFFF
    return bytes([
        class_type & 0xFF,
        member_type & 0xFF,
        user_id & 0xFF, (user_id >> 8) & 0xFF,
        (user_id >> 16) & 0xFF, (user_id >> 24) & 0xFF,
        wl & 0xFF, (wl >> 8) & 0xFF,
        wh & 0xFF, (wh >> 8) & 0xFF,
        0x10,
        0x01,
        sex & 0xFF,
        age & 0xFF,
        height_cm & 0xFF,
        0x00,
    ])


@dataclass
class BlePacket:
    device_class: int
    cmd_type: int
    data_type: int
    total_packets: int
    packet_index: int
    payload16: bytes
    raw: bytes

    @property
    def is_header(self) -> bool:
        return self.packet_index == 0


def parse_ble_packet(data: bytes) -> Optional[BlePacket]:
    if len(data) < 4:
        return None
    l = data[3]
    return BlePacket(
        device_class=data[0],
        cmd_type=data[1],
        data_type=data[2],
        total_packets=(l >> 4) & 0x0F,
        packet_index=l & 0x0F,
        payload16=bytes(data[4:20]) if len(data) >= 20 else bytes(data[4:]),
        raw=bytes(data),
    )


@dataclass
class LogicalFrame:
    device_class: int
    cmd_type: int
    data_type: int
    payload: bytes


class FrameReassembler:
    """Aggrega i chunk BLE per (device_class, cmd_type, data_type) in frame logici."""

    def __init__(self, on_frame: Callable[[LogicalFrame], None]):
        self.on_frame = on_frame
        self._pending: dict[tuple[int, int, int], dict] = {}

    def feed(self, packet: BlePacket) -> None:
        key = (packet.device_class, packet.cmd_type, packet.data_type)
        if packet.is_header:
            total = packet.total_packets
            self._pending[key] = {
                "total": total,
                "header_payload": packet.payload16,
                "chunks": [None] * total,
            }
            if total == 0:
                self._emit(key, b"")
            return

        state = self._pending.get(key)
        if state is None:
            return
        idx = packet.packet_index - 1
        if 0 <= idx < state["total"]:
            state["chunks"][idx] = packet.payload16
            if all(c is not None for c in state["chunks"]):
                payload = b"".join(state["chunks"])
                self._emit(key, payload)

    def _emit(self, key, payload: bytes) -> None:
        self._pending.pop(key, None)
        self.on_frame(LogicalFrame(
            device_class=key[0],
            cmd_type=key[1],
            data_type=key[2],
            payload=payload,
        ))


@dataclass
class WeightFrame:
    flag: int
    timestamp_unix: int
    type: int
    user_id: int
    sex: int
    age: int
    height_cm: int
    weight_kg: float
    unit_raw: int
    fat_pct: Optional[float]
    water_pct: Optional[float]
    bone_kg: Optional[float]
    muscle_kg: Optional[float]
    visceral_fat: Optional[int]
    calorie_kcal: Optional[int]
    bmi: Optional[float]
    raw_hex: str


def _u16_be(data: bytes, offset: int) -> int:
    return (data[offset] << 8) | data[offset + 1]


def _u16_be_scaled(data: bytes, offset: int, divisor: float = 10.0) -> Optional[float]:
    v = _u16_be(data, offset)
    if v == SENTINEL_U16:
        return None
    return v / divisor


def _u32_le(data: bytes, offset: int) -> int:
    return (
        data[offset]
        | (data[offset + 1] << 8)
        | (data[offset + 2] << 16)
        | (data[offset + 3] << 24)
    )


def parse_all_weight_records(payload: bytes, record_size: int = 32) -> list:
    """Parsa TUTTI i record peso da un payload data=4 (multiplo di 32B)."""
    out = []
    for i in range(0, len(payload), record_size):
        chunk = payload[i:i + record_size]
        if len(chunk) < 30:
            break
        wf = parse_weight_payload(chunk)
        if wf is not None:
            out.append(wf)
    return out


def parse_weight_payload(data: bytes) -> Optional[WeightFrame]:
    if len(data) < 30:
        return None

    flag = data[0]
    ts = _u32_le(data, 1)
    typ = data[5]
    user_id = _u32_le(data, 6)
    sex = data[12]
    age = data[13]
    height = data[14]

    weight_raw = ((data[15] & 0x3F) << 8) | data[16]
    unit = (data[15] >> 6) & 0x03
    weight_kg = weight_raw / 10.0
    if unit == 1:
        weight_kg = round(weight_kg * LB_TO_KG, 2)

    visceral = data[25] if data[25] != SENTINEL_U8 else None
    cal_raw = _u16_be(data, 26)
    calorie = cal_raw if cal_raw != SENTINEL_U16 else None

    return WeightFrame(
        flag=flag,
        timestamp_unix=ts,
        type=typ,
        user_id=user_id,
        sex=sex,
        age=age,
        height_cm=height,
        weight_kg=weight_kg,
        unit_raw=unit,
        fat_pct=_u16_be_scaled(data, 17),
        water_pct=_u16_be_scaled(data, 19),
        bone_kg=_u16_be_scaled(data, 21),
        muscle_kg=_u16_be_scaled(data, 23),
        visceral_fat=visceral,
        calorie_kcal=calorie,
        bmi=_u16_be_scaled(data, 28),
        raw_hex=data.hex(),
    )
