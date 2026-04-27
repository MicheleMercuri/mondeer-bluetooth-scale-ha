# Reverse engineering methodology

This document records *how* the protocol described in
[PROTOCOL.md](PROTOCOL.md) was derived. It is intentionally focused on
methodology — the original Java code is **not** redistributed here
(see the legal note at the end).

---

## 1. Starting point

The original Android app `com.mondeer.scale` was no longer functional on
Android 14+ (it crashes silently on the BLE permission flow that changed
in Android 12). The manufacturer never released an updated APK. The
scale itself was perfectly fine: it was just unreachable.

Goal: reach the scale from any platform with BLE 4.0+ (a small Windows
PC near the bathroom, in this case).

Inputs available:
- The legacy APK file (`com.mondeer.scale.apk`)
- A working scale (CE-Link / WanKa C1, branded "Mondeer")
- Patience

---

## 2. Tools

| Tool | What it was used for |
|------|----------------------|
| **JADX** ([github](https://github.com/skylot/jadx)) | Decompile the APK to readable Java |
| **Bleak** ([github](https://github.com/hbldh/bleak)) | BLE client in Python (Windows WinRT backend) |
| **nRF Connect** (Android, on a separate phone) | Sanity-check GATT layout |
| Plain `print(packet.hex())` | Live tracing of frames during PoC |

No on-device dynamic analysis (Frida, Xposed) was needed — the protocol
turned out to be plain enough that static analysis + traffic
observation was sufficient.

---

## 3. Decompilation overview

`jadx-gui com.mondeer.scale.apk` opens the APK and produces a readable
Java tree. The interesting code lives under two packages:

```
com.cheng.bluetoothscale.bluetooth.*    # transport layer
com.cheng.bluetoothscale.c.*            # protocol payloads
```

The OEM is "Cheng-Mondeer" (the package prefix gives it away). The
`bluetoothscale` namespace is stable across many CE-Link OEM firmwares,
so the same code can be used to read other branded scales using the
same chipset.

### 3.1 Key classes

The classes that turned out to be load-bearing for the reverse-engineering
work:

| Class                         | What it does (high level)                              |
|-------------------------------|--------------------------------------------------------|
| `bluetooth.a.c`               | CRC-16/8005 implementation                             |
| `bluetooth.a.b`               | 20-byte BLE packet builder + framing                   |
| `bluetooth.b.*`               | GATT connection state machine                          |
| `c.m`                         | Weight-record parser (32-byte struct)                  |
| `c.ad`                        | Binding-match payload (8 bytes)                        |
| `c.ae`                        | Profile payload (16 bytes per user)                    |
| `c.ab`                        | Time-sync payload (8 bytes)                            |
| `c.aa`                        | ACK packet (cmd=5 wrapper)                             |

The names are JADX-mangled (single-letter package, two-letter classes).
What helped to identify each one was **cross-referencing the GATT
characteristic UUIDs** (`cc08`, `eb00`, `ec01`–`ec05`) used as
`String` constants; once you locate the file that uses `eb00`, the
rest of the transport layer is one or two `.java` files away.

### 3.2 What was extracted (and re-implemented)

For each class above, the **algorithm** was extracted (not the code).
Concretely this meant reading the Java source carefully and
re-writing each routine in idiomatic Python. For example:

- **`bluetooth.a.c::a()`** is a textbook CRC-16/8005, init=0, no
  reflection, no XOR-out. It became `parser.crc16_celink()`.
- **`bluetooth.a.b::a()`** builds the 20-byte packet header. It splits
  payloads into 16-byte chunks, prepends a "header chunk" with
  `(total_packets, struct_size, crc, user_id, user_data)`, and sets
  `seq_byte = (total << 4) | idx`. This became `parser.build_packets()`.
- **`c.m::a(byte[] data)`** parses a 32-byte weight record. The byte
  layout (with mixed endianness — see PROTOCOL §3.4 for the gotcha)
  was lifted directly. This became `parser.parse_weight_payload()`.

The `ad`, `ae`, `ab` payload builders are nothing more than 8 or 16
byte little-endian struct packs — once their layout was understood
they became 5-line Python functions.

---

## 4. Validation against a live scale

Static analysis gives you a hypothesis; only the actual scale tells
you whether the hypothesis is correct. The validation loop was:

1. Run the listener with verbose logging (`pkt dev=… cmd=… data=…`).
2. Step on the scale.
3. Observe the inbound frames; compare them against the predicted
   layout.
4. If the scale stops responding mid-handshake, look for which ACK
   was missed or which payload was malformed.

A few things that came up in this loop:

- **CRC-16 was initially computed wrong** — the original Android code
  has a misleading variable name that suggests `init = 0xFFFF`, but
  the actual implementation uses `init = 0`. The scale silently drops
  packets with a bad CRC, so this manifested as "no response at all".
- **The scale ignores writes on the ACK channel** (`ec05`). Sending
  `cmd=2` payloads on `ec05` looked perfectly fine on the air, but
  the scale never reacted. The fix: round-robin through `ec01..ec04`
  for data and reserve `ec05` exclusively for `cmd=5`.
- **Preliminary records have `sex=age=height=0`**. Initially the
  listener used to drop those as malformed; the trick was realising
  they are a different *kind* of record from the same opcode, and
  the differentiator is `flag` at byte 0 (`0x00` complete, `0x02`
  preliminary).

---

## 5. Why this is legal

This work was carried out **for interoperability purposes** — the only
goal was to make a piece of hardware (the scale) usable again with a
different host platform than the one its manufacturer originally
supported. Specifically:

- **EU**: Article 6 of Directive 2009/24/EC explicitly authorises
  decompilation when "indispensable to obtain the information necessary
  to achieve the interoperability of an independently created computer
  program with other programs", subject to the conditions of Art. 6(1)
  (a)-(c), all of which are met here.
- **USA**: 17 U.S.C. §1201(f) ("Reverse Engineering") permits
  circumvention of technological protection measures for the sole
  purpose of identifying and analysing elements of a program necessary
  to achieve interoperability with other programs.

What this repository **does not** contain and **must not** contain:

- The original APK file
- Decompiled Java source code, in full or in fragments
- Class names, field names, or any other identifiers copied verbatim
  from the Android app
- Any binary asset from the original app (sprites, audio, layouts, …)

What this repository **does** contain:

- A protocol description in plain English
- An independent Python implementation of the *protocol*, written from
  scratch using only the documentation derived from the analysis

If you are reading this and you are CE-Link / WanKa / Mondeer:
contact me, I'm happy to discuss.

---

## 6. Reproducing the analysis on another scale

If you have a scale from the same OEM family but it does not work
out-of-the-box (different name, different service UUID), the same
methodology applies:

1. Get the APK from your phone (`adb pull /data/app/<pkg>/base.apk` on
   a rooted device, or via [APKMirror](https://www.apkmirror.com/) for
   common apps).
2. Run JADX, look for files referencing GATT characteristic UUIDs, and
   identify the equivalent of the table in §3.1.
3. Compare the `cmd_type` / `data_type` opcodes to those documented
   here. Most CE-Link OEM scales differ only in the GATT UUID and the
   advertising name — the rest of the transport is the same.
4. Adjust `SERVICE_UUID` and `NAME_HINTS` in `listener/scale_listener.py`
   accordingly.

If you do this for a model that is not yet known to work, please open
a PR with the new UUID/name + a short note. It's a 2-line change that
makes the listener support one more product.
