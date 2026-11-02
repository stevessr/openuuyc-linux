# Per-user opt-in for the private window extension. No UAC, driver or service.
param([Parameter(Mandatory=$true)]
    [ValidateSet("Install", "Remove")]
    [string]$Action)

$ErrorActionPreference = "Stop"
if (-not $env:LOCALAPPDATA -or -not [System.IO.Path]::IsPathRooted($env:LOCALAPPDATA)) {
    throw "LOCALAPPDATA is unavailable."
}
$directory = Join-Path $env:LOCALAPPDATA "OpenUUYC"
$marker = Join-Path $directory "private-window-control.enabled"
if ($Action -eq "Install") {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    [System.IO.File]::WriteAllText($marker, "enabled")
    Write-Host "Window extension enabled for this Windows user. Restart OpenUUYC."
} else {
    Remove-Item -LiteralPath $marker -Force -ErrorAction SilentlyContinue
    Write-Host "Window extension disabled. Existing sessions must reconnect."
}
