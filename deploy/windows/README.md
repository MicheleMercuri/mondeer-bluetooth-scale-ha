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
