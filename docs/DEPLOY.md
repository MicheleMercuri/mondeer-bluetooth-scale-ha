# Deployment

The listener is a long-running Python process that needs to be alive
24/7 on a machine with BLE access close to the scale. This document
covers running it as a service on **Windows** (production-tested) and
on **Linux** (untested at the time of writing — PRs welcome).

---

## Hardware requirements

- Bluetooth **4.0** or newer (BT 5.0 strongly recommended for
  reliability — see §4 below)
- Within ~5 metres of the scale, with no thick walls in between
- Always-on, or at least on whenever someone might step on the scale
- Any OS that Python 3.10+ runs on. macOS is also fine for development
  but is not recommended for a production deployment

A Mini PC, a Raspberry Pi 4 with a USB BT dongle, an Intel NUC — all
fine. Avoid laptops with aggressive USB power management unless you
disable it (see §4.2).

---

## Windows (Task Scheduler)

The repo ships PowerShell scripts in `deploy/windows/` that:

- create a Python venv outside the project directory (so it is not
  synced by Google Drive / OneDrive / Dropbox)
- install the dependencies
- register a Scheduled Task that auto-starts at logon and auto-restarts
  on crash (`RestartCount: 999`, `RestartInterval: 1 minute`)

### One-shot install

Open **PowerShell as Administrator** and run:

```powershell
& "C:\path\to\repo\deploy\windows\setup.ps1"
```

This auto-localises (no hard-coded paths), creates the venv under
`%LOCALAPPDATA%\BilanciaMondeer\venv`, installs `bleak` + `paho-mqtt` +
`PyYAML`, and registers the task `MondeerScaleListener`.

After the first install, **edit `listener/config.yaml`** with your
HA token, MQTT credentials and family profiles, then restart the task:

```powershell
Stop-ScheduledTask  -TaskName MondeerScaleListener
Start-ScheduledTask -TaskName MondeerScaleListener
```

### Day-to-day operations

```powershell
# Status
Get-ScheduledTask -TaskName MondeerScaleListener | Select-Object State, LastTaskResult

# Tail log
Get-Content "$env:LOCALAPPDATA\BilanciaMondeer\listener.log" -Tail 50 -Wait

# Metrics (success rate, last failure, recovery count)
Get-Content "$env:LOCALAPPDATA\BilanciaMondeer\metrics.json"

# Uninstall
& "C:\path\to\repo\deploy\windows\uninstall_task.ps1"
```

### About the Task Scheduler RunLevel

The task is registered with **`-RunLevel Highest`**. The reason is the
optional auto-recovery of `bthserv` (`Restart-Service bthserv -Force`),
which requires admin. If you do not need auto-recovery
(`enable_auto_recovery: false` in `config.yaml`, the default), you can
downgrade to `-RunLevel Limited` by editing
`deploy/windows/install_task.ps1` before running it.

---

## Linux (systemd, untested)

A starting-point unit file is provided as
`deploy/linux/bilancia-mondeer.service.example`. Adapt the paths and
the user, then:

```bash
sudo cp deploy/linux/bilancia-mondeer.service.example /etc/systemd/system/bilancia-mondeer.service
sudo systemctl daemon-reload
sudo systemctl enable --now bilancia-mondeer
journalctl -u bilancia-mondeer -f
```

Linux notes:

- BlueZ ≥ 5.50 is required by recent Bleak.
- The service user needs membership in the `bluetooth` group, or the
  unit must run as root (less ideal).
- Auto-recovery via `Restart-Service` is Windows-only; on Linux you'd
  do `sudo systemctl restart bluetooth`. Patches welcome.

---

## Bluetooth chipset reliability

The listener is robust against transient BLE issues (auto-reconnect,
backoff, optional auto-recovery), but the underlying chipset matters
a lot for how often you have to think about it.

### 4.1 Tested known-good

| Chipset                       | Stability    | Notes                                  |
|-------------------------------|--------------|----------------------------------------|
| Realtek RTL8761B (BT 5.0/5.1) | excellent    | Found in TP-Link UB500, ASUS USB-BT500 |
| Intel AX200 / AX210           | excellent    | Recent integrated WiFi+BT modules      |

### 4.2 Tested problematic

| Chipset                       | Issue                                          |
|-------------------------------|------------------------------------------------|
| Intel Wireless 7260 (BT 4.0)  | "Stuck radio" after a few sessions; needs `Restart-Service bthserv` to recover. EOL since 2019, no updated drivers exist. Auto-recovery in this listener was written specifically for this chip. |

If you are starting fresh and your minipc has an old BT 4.0 chipset,
**a USB BT 5.0 dongle costs €10 and removes the entire class of
problems** the auto-recovery code is there to mitigate. Specifically
recommended:

- TP-Link UB500
- ASUS USB-BT500
- (any other Realtek RTL8761B-based dongle)

After plugging in the dongle, **disable the integrated BT** in Device
Manager so Windows uses only the dongle. Otherwise Windows may
arbitrarily route BLE traffic through whichever radio it sees first.

### 4.3 Power management

Disable "Allow the computer to turn off this device to save power" on
the BT adapter in Device Manager (Windows) — the radio going into low
power mid-session is a common cause of unexpected disconnects on
laptops and mini PCs.

### 4.4 Conflicts with other BT devices

If the same machine has an **Echo / Alexa / Bluetooth speaker**
connected for audio playback, the radio is shared and BLE scans get
starved. Disconnect the audio device from BT (or use an external USB
dongle dedicated to the listener).

---

## Troubleshooting checklist

1. Is the task running?
   `Get-ScheduledTask -TaskName MondeerScaleListener | Select-Object State`
2. Is the BT adapter healthy?
   `Get-PnpDevice -Class Bluetooth | Where-Object Status -ne 'OK'`
3. Is the listener seeing the scale advertising?
   Tail the log while stepping on the scale: you should see
   `scale advertising: <MAC> name='WanKa C1' rssi=<-60..-80>`.
4. Does the connect succeed?
   You should see `connect OK at attempt N` within a few seconds.
5. Does MQTT publish go through?
   Look for `MQTT publish ... OK (weight=...kg, mid=N)` in the log.

If step 3 fails: BT scan problem (see §4). If step 4 fails consistently:
the chipset is stuck — try `Restart-Service bthserv -Force` or reboot.
If step 5 fails: MQTT credentials in `config.yaml` are wrong, or the
broker is unreachable.
