# BLE protocol — Mondeer / WanKa C1 (CE-Link family)

This document describes the on-the-wire protocol used by the
Mondeer / WanKa C1 BLE bathroom scale, derived from observing the BLE
traffic of the original Android app and confirmed by the Java decompiled
sources of `com.mondeer.scale`. See
[REVERSE_ENGINEERING.md](REVERSE_ENGINEERING.md) for the methodology.

> Naming: the chipset is "CE-Link"; the manufacturer brands it as
> "WanKa" and resells it under several private labels (Mondeer is one).
> Internally the firmware identifies itself as `11030BT-Scale`.

---

## 1. Discovery

### 1.1 Advertising

- BT name: `WanKa C1` (sometimes `Cheng-Mondeer`, `CE-Link`, …)
- Primary service UUID advertised: **`0000cc08-0000-1000-8000-00805f9b34fb`**
- The scale only advertises while powered on (~10 s per weighing). It
  does **not** support persistent advertising / passive sleep mode —
  the only way to capture readings is a permanent active scanner that
  catches the brief advertising window.

### 1.2 GATT layout

Service `0xcc08` exposes 5 characteristics (handles vary):

| UUID short | Properties              | Used for                        |
|------------|-------------------------|---------------------------------|
| `eb00`     | notify, indicate        | Inbound frames from the scale   |
| `ec01`     | write, write-w/o-resp.  | Outbound, channel A (round-r.)  |
| `ec02`     | write, write-w/o-resp.  | Outbound, channel B             |
| `ec03`     | write, write-w/o-resp.  | Outbound, channel C             |
| `ec04`     | write, write-w/o-resp.  | Outbound, channel D             |
| `ec05`     | write, write-w/o-resp.  | Outbound, **ACK channel** only  |

The 4 outbound channels `ec01..ec04` are functionally interchangeable;
the original app round-robins through them to reduce per-channel
contention on cheap controllers. `ec05` is reserved for ACK packets
(`cmd=5`) — sending data frames on it will be silently ignored.

**Always use `write-without-response`** when the property is available:
the full handshake must complete inside the ~10 s power-on window,
and waiting for an ACK on every individual packet eats ~1.5 s each.

---

## 2. Packet framing

Each BLE notification or write carries a **20-byte packet**:

```
 byte 0   1   2   3   4 5 6 7 8 9 ............ 19
       ┌───┬───┬───┬───┬─────────────────────────┐
       │dc │cmd│dt │ s │       payload (16 B)    │
       └───┴───┴───┴───┴─────────────────────────┘
```

| Field | Bits | Description                                                    |
|-------|------|----------------------------------------------------------------|
| `dc`  | 8    | Device class. **3 = scale**, 1 = wristband (other CE-Link OEM) |
| `cmd` | 8    | Command type. **2 = data**, 4 = request, **5 = ACK**           |
| `dt`  | 8    | Data type / opcode (see §3)                                    |
| `s`   | 8    | Sequence: high nibble = total chunks, low nibble = chunk index |
| `payload` | 128 | 16 bytes of frame data                                       |

A "logical frame" can therefore be longer than 16 bytes: it is split
into N + 1 packets (1 header + N data chunks). The header chunk has
`s = (total << 4) | 0` and a special payload layout (see §2.1).

### 2.1 Header chunk payload (16 bytes)

```
offset  size  field
   0     1    n_records         number of structs in the payload
   1     1    chunk_struct_size size of each struct (used to validate)
   2     2    crc16             CRC-16/8005 over the *reassembled* payload
   4     4    user_id_le        u32 LE, 0 if not user-bound
   8     8    user_data         opcode-specific (e.g. family_id for data=102)
```

`crc16` polynomial is **`0x8005`**, init `0`, no input/output reflection,
no XOR-out. Reference Python implementation: `parser.crc16_celink()`.

### 2.2 Data chunks

Each subsequent packet carries 16 raw bytes of payload. Chunk index in
`s` low nibble starts at 1. After all chunks have arrived, the
reassembled buffer is the logical frame.

If a logical frame fits in 16 bytes, only one data chunk follows the
header. If it fits in 0 bytes (an ACK is one example), the header is
sent alone with `total = 0`.

---

## 3. Opcodes (`data_type`)

Inbound (scale → host):

| `data_type` | Meaning                              | Direction          |
|-------------|--------------------------------------|--------------------|
| 2           | Device info (model name, firmware)   | scale → host       |
| 3           | (heartbeat / status, payload zero)   | scale → host       |
| 4           | **Weight + body composition record** | scale → host       |
| 102         | Profiles registration (ack only)     | both ways (cmd=5)  |
| 103         | Time sync (ack only)                 | both ways (cmd=5)  |
| 104         | Binding match (ack only)             | both ways (cmd=5)  |

Outbound (host → scale):

| `data_type` | Meaning                              | Payload struct        |
|-------------|--------------------------------------|-----------------------|
| 102         | Family profiles                      | N × 16-byte `ae`      |
| 103         | Time sync                            | 8-byte `ab`           |
| 104         | Binding match                        | 8-byte `ad`           |

### 3.1 Binding match (`cmd=2 data=104`) — `ad` payload

```
offset  size  field
   0     1    type        usually 0
   1     1    subtype     1 = "register this host as the scale's binding"
   2     4    family_id   u32 LE — any persistent family identifier
   6     2    last_id     last record id seen, 0 on first connect
```

### 3.2 Family profiles (`cmd=2 data=102`) — N × `ae` payload

Each profile is 16 bytes:

```
offset  size  field
   0     1    class_type      1 = admin, 0 = standard member
   1     1    member_type     same as user_id (echoed)
   2     4    user_id         u32 LE, 1..8
   6     2    weight_low_kg   u16 LE, kg × 10
   8     2    weight_high_kg  u16 LE, kg × 10
  10     1    flags           always 0x10
  11     1    flags           always 0x01
  12     1    sex             1 = male, 0 = female
  13     1    age             years, u8
  14     1    height_cm       cm, u8
  15     1    pad             0x00
```

The header's `user_data` carries `family_id` (4 bytes LE) + 4 zero bytes.
`chunk_struct_size = 16`, `n_records = number of profiles`.

### 3.3 Time sync (`cmd=2 data=103`) — `ab` payload

```
offset  size  field
   0     1    year - 2000
   1     1    month     1..12
   2     1    day       1..31
   3     1    weekday   0=Sun … 6=Sat
   4     1    hour      0..23
   5     1    minute    0..59
   6     1    second    0..59
   7     1    flags     0x01
```

### 3.4 Weight record (`cmd=2 data=4`) — 32-byte struct

The most important opcode. The scale sends **one or more** 32-byte
records concatenated in the payload:

```
offset  size  field           notes
   0     1    flag            0x00 = complete BIA, 0x02 = preliminary (no BIA)
   1     4    timestamp_unix  u32 LE, scale's RTC at measurement
   5     1    type            usually 0x01
   6     4    user_id         u32 LE, 0 on preliminary records
  10     2    flags
  12     1    sex             echoed; 0 on preliminary records
  13     1    age             echoed; 0 on preliminary records
  14     1    height_cm       echoed; 0 on preliminary records
  15..16 2    weight + unit   ((b15 & 0x3F) << 8) | b16 = weight × 10
                              (b15 >> 6) & 0x03 = unit (0 = kg, 1 = lb)
  17     2    fat_pct × 10    u16 BE; 0xFFFF if unavailable
  19     2    water_pct × 10  u16 BE; 0xFFFF if unavailable
  21     2    bone_kg × 10    u16 BE; 0xFFFF if unavailable
  23     2    muscle_kg × 10  u16 BE; 0xFFFF if unavailable
  25     1    visceral_fat    u8;    0xFF   if unavailable
  26     2    calorie_kcal    u16 BE; 0xFFFF if unavailable
  28     2    bmi × 10        u16 BE; 0xFFFF if unavailable
  30     2    pad
```

> **Endianness oddity**: the 4-byte timestamp at offset 1 is little-endian
> while every body composition `u16` from offset 17 onward is big-endian.
> This is an artifact of the original chip's vendor-specific ABI; not
> something we can change.

### 3.5 Preliminary vs complete records

The scale typically transmits **two** records per weighing:

1. **Preliminary** (~1 s after step-on, `flag = 0x02`): weight only,
   BIA fields are sentinel `0xFFFF`, profile fields zeroed.
2. **Complete** (~5–10 s later, `flag = 0x00`): weight echoed, plus
   complete body composition, plus profile fields filled with the user
   matched by the scale.

If the user steps off the scale early, only the preliminary record is
emitted. The listener detects this by `fat_pct is None` and merges the
last known body composition (per profile) so the dashboard does not
blank out — see `ha_push.get_last_body_comp()`.

---

## 4. Handshake sequence

Successful per-session sequence (host = listener, scale = bathroom scale):

```
   host                                     scale
    │                                         │
    │  (BLE connect)                          │
    │────────────────────────────────────────▶│
    │  WAKE_PACKET on ec01..ec04              │
    │────────────────────────────────────────▶│
    │                            cmd=2 data=2 │  device info
    │◀────────────────────────────────────────│
    │  cmd=5 data=2  ACK                      │
    │────────────────────────────────────────▶│
    │  cmd=2 data=104 (binding, ad payload)   │
    │────────────────────────────────────────▶│
    │                          cmd=5 data=104 │  binding ACK
    │◀────────────────────────────────────────│
    │  cmd=2 data=102 (profiles, N × ae)      │
    │────────────────────────────────────────▶│
    │                          cmd=5 data=102 │  profiles ACK
    │◀────────────────────────────────────────│
    │  cmd=2 data=103 (time sync, ab payload) │
    │────────────────────────────────────────▶│
    │                          cmd=5 data=103 │  time ACK
    │◀────────────────────────────────────────│
    │                  cmd=2 data=4 (weight)  │  preliminary
    │◀────────────────────────────────────────│
    │  cmd=5 data=4  ACK                      │
    │────────────────────────────────────────▶│
    │             cmd=2 data=4 (weight + BIA) │  complete
    │◀────────────────────────────────────────│
    │  cmd=5 data=4  ACK                      │
    │────────────────────────────────────────▶│
    │                                  (power off)
    │◀── disconnect ──────────────────────────┤
```

The whole sequence must fit inside ~10 s. With `write-without-response`
the host side is ~50 ms; the rest is BIA computation time on the scale.

---

## 5. Quirks and gotchas

- **No persistent pairing**: the scale does not bond at the LE Security
  Manager level. Windows still does "transparent pairing" the first
  time it sees the device, which can take 20–30 s. After that, connects
  are sub-second.
- **No GATT MTU negotiation**: assume 23-byte ATT MTU. Each notification
  carries exactly 20 useful bytes.
- **No subscription persistence**: notifications must be re-enabled on
  every connect (Bleak does this automatically).
- **Idle disconnect**: if no data flows for ~30 s the scale powers off
  and disconnects from its side. This is the only way the listener
  knows the weighing is finished.
