# Stop and remove the MondeerScaleListener Scheduled Task.
# Run as Administrator.

$ErrorActionPreference = "Continue"
$TaskName = "MondeerScaleListener"

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { Write-Error "Run as Administrator."; exit 1 }

Write-Host "Stopping task..."
Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue

Write-Host "Removing task..."
Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue

Write-Host "Done."
Write-Host ""
Write-Host "Note: the venv at $env:LOCALAPPDATA\BilanciaMondeer\venv is left in place."
Write-Host "Delete it manually if you want to fully uninstall."
