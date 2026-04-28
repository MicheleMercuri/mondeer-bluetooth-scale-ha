# Watchdog del listener bilancia.
#
# Logica:
#   1. Legge %LOCALAPPDATA%\BilanciaMondeer\heartbeat.json (scritto dal
#      thread status_publisher ogni 30s).
#   2. Se ts > MAX_AGE_SEC fa, considera il processo zombie.
#   3. Recovery: stop task → kill pythonw → restart bthserv → start task.
#   4. Notifica Telegram via HA REST.
#
# Configurato come Scheduled Task `MondeerScaleWatchdog` con repeat ogni 2 min.

param(
    [int]$MaxAgeSec = 300,    # 5 minuti
    [string]$HeartbeatPath = "$env:LOCALAPPDATA\BilanciaMondeer\heartbeat.json"
)

$ErrorActionPreference = "Continue"
$logFile = "$env:LOCALAPPDATA\BilanciaMondeer\watchdog.log"

function Write-Log($msg) {
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $msg"
    Write-Host $line
    Add-Content -Path $logFile -Value $line
}

function Send-TelegramAlert($msg) {
    # Usa l'API HA per inviare un messaggio Telegram tramite il bot
    # configurato in HA. Se il file ha-config.json (creato manualmente)
    # contiene base_url + token, fa la POST. Se non esiste, skippa
    # silenziosamente (il watchdog non deve fallire per la notifica).
    $cfgPath = "$env:LOCALAPPDATA\BilanciaMondeer\ha-config.json"
    if (-not (Test-Path $cfgPath)) { return }
    try {
        $cfg = Get-Content $cfgPath -Raw | ConvertFrom-Json
        $body = @{
            message = "[Watchdog] $msg"
            data    = @{ chat_id = [int64]$cfg.telegram_chat_id }
        } | ConvertTo-Json -Compress
        $headers = @{ Authorization = "Bearer $($cfg.token)" }
        Invoke-RestMethod -Uri "$($cfg.base_url)/api/services/telegram_bot/send_message" `
            -Method Post -Headers $headers -ContentType "application/json" -Body $body `
            -TimeoutSec 5 | Out-Null
    } catch {
        Write-Log "Telegram alert failed: $_"
    }
}

function Restart-Listener {
    Write-Log "Recovery: stopping task..."
    Stop-ScheduledTask -TaskName MondeerScaleListener -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    Write-Log "Recovery: killing pythonw processes..."
    Get-Process pythonw -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    Write-Log "Recovery: restarting bthserv..."
    Restart-Service bthserv -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 4
    Write-Log "Recovery: starting task..."
    Start-ScheduledTask -TaskName MondeerScaleListener
    Start-Sleep -Seconds 4
}

# === main ===

if (-not (Test-Path $HeartbeatPath)) {
    Write-Log "heartbeat file not found at $HeartbeatPath — listener has never run? skipping"
    exit 0
}

$hb = Get-Content $HeartbeatPath -Raw | ConvertFrom-Json
$nowEpoch = [int64](Get-Date -UFormat %s)
$ageSec = $nowEpoch - [int64]$hb.ts

Write-Log "heartbeat age=${ageSec}s phase=$($hb.phase) pid=$($hb.pid)"

if ($ageSec -gt $MaxAgeSec) {
    Write-Log "ZOMBIE detected (heartbeat ${ageSec}s old > ${MaxAgeSec}s). Triggering recovery."
    Send-TelegramAlert "Listener zombie detected (heartbeat ${ageSec}s stale, phase=$($hb.phase)). Auto-restart in progress."
    Restart-Listener

    # Verifica recovery
    Start-Sleep -Seconds 6
    if (Test-Path $HeartbeatPath) {
        $hb2 = Get-Content $HeartbeatPath -Raw | ConvertFrom-Json
        $newAge = [int64](Get-Date -UFormat %s) - [int64]$hb2.ts
        if ($newAge -lt 60) {
            Write-Log "Recovery OK: new heartbeat age=${newAge}s"
            Send-TelegramAlert "Listener restored (new heartbeat age ${newAge}s)."
        } else {
            Write-Log "Recovery did not produce a fresh heartbeat — manual intervention may be needed"
            Send-TelegramAlert "Listener recovery FAILED — heartbeat still stale after restart."
        }
    }
}
