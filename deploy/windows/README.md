# Windows deployment

## First install

1. Open **PowerShell as Administrator**.
2. Run:
   ```powershell
   & "<repo>\deploy\windows\setup.ps1"
   ```
3. Copy `listener\config.example.yaml` to `listener\config.yaml` and fill
   in your HA / MQTT / family settings.
4. Restart the task to pick up the new config:
   ```powershell
   Stop-ScheduledTask  -TaskName MondeerScaleListener
   Start-ScheduledTask -TaskName MondeerScaleListener
   ```

## What the scripts do

- `setup.ps1` — checks Python, creates a venv under
  `%LOCALAPPDATA%\BilanciaMondeer\venv`, installs requirements, then
  invokes `install_task.ps1`.
- `install_task.ps1` — registers the Scheduled Task `MondeerScaleListener`
  to run at user logon, with restart-on-crash and `RunLevel Highest`
  (needed only for `enable_auto_recovery: true`).
- `uninstall_task.ps1` — stops and unregisters the task. Leaves the
  venv in place; delete it manually if you want a full uninstall.

## File locations

| Path | Contents |
|------|----------|
| `<repo>\listener\` | Python sources + `config.yaml` |
| `%LOCALAPPDATA%\BilanciaMondeer\venv\` | Python virtual environment |
| `%LOCALAPPDATA%\BilanciaMondeer\listener.log` | Rolling log (2 MB × 4) |
| `%LOCALAPPDATA%\BilanciaMondeer\last_pushed.json` | Anti-duplicate + body comp memory |
| `%LOCALAPPDATA%\BilanciaMondeer\metrics.json` | Connect/recovery counters |
| `%LOCALAPPDATA%\BilanciaMondeer\heartbeat.json` | Liveness ping (status_pub thread, every 30 s) |
| `%LOCALAPPDATA%\BilanciaMondeer\watchdog.log` | Watchdog activity log |
| `%LOCALAPPDATA%\BilanciaMondeer\ha-config.json` | Optional Telegram alert config (NOT in repo) |

## Optional: zombie watchdog & nightly quiet hours

The listener occasionally gets stuck when the BLE adapter has been idle
for hours (a known weakness of cheap RTL chips on Windows). Three extra
scheduled tasks make the deployment self-healing:

| Task | Schedule | What it does |
|------|----------|---------------|
| `MondeerScaleListener-Sleep` | daily at 23:00 | Stops the listener task and kills any leftover `pythonw.exe`. |
| `MondeerScaleListener-Wake` | daily at 05:30 | Restarts `bthserv` then starts the listener task again. |
| `MondeerScaleWatchdog` | every 2 minutes | Reads `heartbeat.json`. If the timestamp is older than 5 minutes it does a full recovery (stop task → kill `pythonw` → restart `bthserv` → start task) and sends a Telegram alert. |

The watchdog script lives at
[`deploy/windows/watchdog.ps1`](watchdog.ps1). To register the three tasks
manually:

```powershell
# Sleep at 23:00
$a = New-ScheduledTaskAction -Execute "powershell.exe" -Argument '-NoProfile -WindowStyle Hidden -Command "Stop-ScheduledTask -TaskName MondeerScaleListener -ErrorAction SilentlyContinue; Get-Process pythonw -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue"'
Register-ScheduledTask -TaskName "MondeerScaleListener-Sleep" -Action $a `
    -Trigger (New-ScheduledTaskTrigger -Daily -At "23:00") -Force

# Wake at 05:30
$a = New-ScheduledTaskAction -Execute "powershell.exe" -Argument '-NoProfile -WindowStyle Hidden -Command "Restart-Service bthserv -Force -ErrorAction SilentlyContinue; Start-Sleep -Seconds 5; Start-ScheduledTask -TaskName MondeerScaleListener"'
Register-ScheduledTask -TaskName "MondeerScaleListener-Wake" -Action $a `
    -Trigger (New-ScheduledTaskTrigger -Daily -At "05:30") -Force

# Watchdog every 2 minutes
$wd = "<repo>\deploy\windows\watchdog.ps1"
$a = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$wd`""
$t = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
    -RepetitionInterval (New-TimeSpan -Minutes 2)
Register-ScheduledTask -TaskName "MondeerScaleWatchdog" -Action $a -Trigger $t -Force
```

To enable Telegram alerts on watchdog actions, drop a
`%LOCALAPPDATA%\BilanciaMondeer\ha-config.json` like:

```json
{
  "base_url": "http://homeassistant.local:8123",
  "token": "<long-lived access token>",
  "telegram_chat_id": 123456789
}
```

Without that file the watchdog still recovers the listener — it just
skips the notification.

## Day-to-day commands

```powershell
# Status
Get-ScheduledTask -TaskName MondeerScaleListener | Select-Object State

# Tail log
Get-Content "$env:LOCALAPPDATA\BilanciaMondeer\listener.log" -Tail 50 -Wait

# Metrics
Get-Content "$env:LOCALAPPDATA\BilanciaMondeer\metrics.json"

# Restart after editing config.yaml
Stop-ScheduledTask  -TaskName MondeerScaleListener
Start-ScheduledTask -TaskName MondeerScaleListener
```

See [`docs/DEPLOY.md`](../../docs/DEPLOY.md) for troubleshooting and
chipset-related notes.
