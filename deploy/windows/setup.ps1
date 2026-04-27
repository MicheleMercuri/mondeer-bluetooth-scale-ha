# One-shot installer for the Mondeer/WanKa C1 BLE listener on Windows.
#
# Creates a Python venv outside the repo (so it is not synced by Google
# Drive / OneDrive / Dropbox), installs the dependencies, and registers
# a Scheduled Task that starts at logon and auto-restarts on crash.
#
# Run from PowerShell as Administrator:
#   & "<repo>\deploy\windows\setup.ps1"

$ErrorActionPreference = "Stop"

# Auto-locate paths from this script's location.
$ScriptRoot   = $PSScriptRoot
$RepoRoot     = (Resolve-Path "$ScriptRoot\..\..").Path
$ListenerDir  = Join-Path $RepoRoot "listener"

# The venv is intentionally placed under %LOCALAPPDATA%, NOT inside the
# repo (which may sit on a synced cloud drive).
$LocalRoot    = "$env:LOCALAPPDATA\BilanciaMondeer"
$Venv         = "$LocalRoot\venv"
$Pythonw      = "$Venv\Scripts\pythonw.exe"
$PipExe       = "$Venv\Scripts\pip.exe"
$Reqs         = "$ListenerDir\requirements.txt"
New-Item -ItemType Directory -Force -Path $LocalRoot | Out-Null

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Error "Please run as Administrator (right-click PowerShell -> Run as administrator)."
    exit 1
}

if (-not (Test-Path $RepoRoot)) {
    Write-Error "Repo root not found: $RepoRoot"
    exit 1
}

# 1) Verify Python 3.10+ via the py launcher.
Write-Host "Checking for Python..."
$pyCmd = $null
foreach ($v in @("-3.12", "-3.11", "-3.10", "-3")) {
    try {
        $ver = & py $v --version 2>&1
        if ($ver -match "Python") {
            Write-Host "  Found via py $v -> $ver"
            $pyCmd = "py $v"
            break
        }
    } catch {}
}
if (-not $pyCmd) {
    Write-Error "Python 3.10+ not found. Install from https://python.org"
    exit 1
}

# 2) Create venv if missing or stale.
$venvOk = $false
if (Test-Path $Pythonw) {
    try {
        $check = & $Pythonw -c "import sys; print(sys.version)" 2>&1
        if ($check -match "^\d") { $venvOk = $true }
    } catch {}
}
if (-not $venvOk) {
    Write-Host "Creating venv (removing previous if any)..."
    if (Test-Path $Venv) {
        Remove-Item -Recurse -Force $Venv -ErrorAction SilentlyContinue
    }
    & cmd /c "$pyCmd -m venv `"$Venv`""
    if (-not (Test-Path $Pythonw)) { Write-Error "venv creation failed"; exit 1 }
    Write-Host "  venv created in $Venv"
} else {
    Write-Host "  venv exists, OK"
}

# 3) Install/upgrade dependencies.
Write-Host "Installing dependencies..."
& $PipExe install --quiet --upgrade pip
& $PipExe install --quiet -r $Reqs
Write-Host "  packages installed"

# 4) Verify config exists.
$ConfigFile = "$ListenerDir\config.yaml"
if (-not (Test-Path $ConfigFile)) {
    Write-Warning "config.yaml not found at $ConfigFile"
    Write-Warning "Copy listener\config.example.yaml to listener\config.yaml and edit it BEFORE the listener will work properly."
}

# 5) Register the Scheduled Task.
Write-Host "Registering Scheduled Task..."
& "$ScriptRoot\install_task.ps1"

Write-Host ""
Write-Host "Setup complete. The task 'MondeerScaleListener' starts at logon."
Write-Host "Logs: $env:LOCALAPPDATA\BilanciaMondeer\listener.log"
Write-Host ""
if (-not (Test-Path $ConfigFile)) {
    Write-Host "NEXT STEP: copy listener\config.example.yaml to listener\config.yaml," -ForegroundColor Yellow
    Write-Host "edit it with your HA + MQTT credentials and family profiles," -ForegroundColor Yellow
    Write-Host "then restart the task:" -ForegroundColor Yellow
    Write-Host "    Stop-ScheduledTask  -TaskName MondeerScaleListener" -ForegroundColor Yellow
    Write-Host "    Start-ScheduledTask -TaskName MondeerScaleListener" -ForegroundColor Yellow
}
