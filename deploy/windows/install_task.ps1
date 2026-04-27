# Register the listener as a Windows Scheduled Task.
# Runs as the current user (NOT SYSTEM, which has no BLE access),
# starts at user logon, restarts on crash.
# Requires Administrator (so the task can be created with -RunLevel Highest,
# which is needed for the optional auto-recovery feature in the listener).

$ErrorActionPreference = "Stop"

$ScriptRoot   = $PSScriptRoot
$RepoRoot     = (Resolve-Path "$ScriptRoot\..\..").Path
$ListenerDir  = Join-Path $RepoRoot "listener"

$LocalRoot    = "$env:LOCALAPPDATA\BilanciaMondeer"
$Pythonw      = "$LocalRoot\venv\Scripts\pythonw.exe"
$Module       = "listener.scale_listener"
$WorkDir      = $RepoRoot
$TaskName     = "MondeerScaleListener"
$User         = "$env:USERDOMAIN\$env:USERNAME"

if (-not (Test-Path $Pythonw)) {
    Write-Error "venv Python not found at $Pythonw - run setup.ps1 first"
    exit 1
}
if (-not (Test-Path $ListenerDir)) {
    Write-Error "listener dir not found: $ListenerDir"
    exit 1
}

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { Write-Error "Run as Administrator."; exit 1 }

# Remove any existing task with the same name.
$existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Existing task found, removing..."
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}

Write-Host "Registering task '$TaskName' for user $User..."

$action = New-ScheduledTaskAction `
    -Execute $Pythonw `
    -Argument "-m $Module" `
    -WorkingDirectory $WorkDir

$trigger = New-ScheduledTaskTrigger -AtLogOn -User $User

$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit (New-TimeSpan -Hours 0)

# RunLevel Highest (admin) is required if you want enable_auto_recovery: true
# in config.yaml (Restart-Service bthserv needs admin). If you do not need
# auto-recovery, you can change this to "Limited".
$principal = New-ScheduledTaskPrincipal `
    -UserId $User `
    -LogonType Interactive `
    -RunLevel Highest

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Principal $principal `
    -Description "Mondeer/WanKa C1 BLE listener" | Out-Null

Write-Host "Starting task..."
Start-ScheduledTask -TaskName $TaskName

Start-Sleep -Seconds 2
$info = Get-ScheduledTaskInfo -TaskName $TaskName
$task = Get-ScheduledTask -TaskName $TaskName

Write-Host ""
Write-Host "Task '$TaskName' registered."
Write-Host "  State: $($task.State)"
Write-Host "  LastRunTime:    $($info.LastRunTime)"
Write-Host "  LastTaskResult: $($info.LastTaskResult)  (0 = OK / running)"
Write-Host ""
Write-Host "Useful commands:"
Write-Host "  Stop:      Stop-ScheduledTask -TaskName $TaskName"
Write-Host "  Start:     Start-ScheduledTask -TaskName $TaskName"
Write-Host "  State:     Get-ScheduledTask -TaskName $TaskName | Select-Object State"
Write-Host "  Last run:  Get-ScheduledTaskInfo -TaskName $TaskName"
Write-Host "  Tail log:  Get-Content `"$env:LOCALAPPDATA\BilanciaMondeer\listener.log`" -Tail 30 -Wait"
Write-Host "  Uninstall: $ScriptRoot\uninstall_task.ps1 (admin)"
