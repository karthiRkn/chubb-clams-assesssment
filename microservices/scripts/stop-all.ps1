<#
  Stops the services that start-all.ps1 started, using the PID files in microservices\logs.
  It only stops those recorded PIDs and only when the process is still a java process; it never touches anything else.
#>
$ErrorActionPreference = 'Stop'
$logs = Join-Path (Split-Path -Parent $PSScriptRoot) 'logs'
$pidFiles = @()
if (Test-Path $logs) { $pidFiles = Get-ChildItem -Path $logs -Filter '*.pid' }
if (-not $pidFiles) { Write-Host 'No PID files found: nothing to stop.'; return }

foreach ($file in $pidFiles) {
    $id = [int](Get-Content $file.FullName)
    $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($proc -and $proc.ProcessName -like 'java*') {
        Write-Host "Stopping $($file.BaseName) (PID $id)"
        Stop-Process -Id $id -Force
    } else {
        Write-Host "$($file.BaseName): PID $id is not running (or is not java); leaving it alone."
    }
    Remove-Item $file.FullName
}
