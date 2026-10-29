<#
    Opens TCP port 8787 so phones on your network can reach the Axzora sync
    server running on this laptop.

    Run once, in an ELEVATED PowerShell (Run as administrator):
        powershell -ExecutionPolicy Bypass -File .\allow-firewall.ps1

    The rule is scoped to Private networks only, so the port is NOT opened on
    public Wi-Fi. To undo it later:
        Remove-NetFirewallRule -DisplayName "Axzora Sync Server"
#>

$ErrorActionPreference = 'Stop'
$name = 'Axzora Sync Server'
$port = 8787

$identity  = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "This script needs administrator rights." -ForegroundColor Red
    Write-Host "Right-click PowerShell -> Run as administrator, then run it again."
    exit 1
}

$existing = Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Firewall rule '$name' already exists. Nothing to do." -ForegroundColor Yellow
} else {
    New-NetFirewallRule -DisplayName $name `
        -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port `
        -Profile Private -Description 'Axzora OS family location sync' | Out-Null
    Write-Host "Created firewall rule '$name' for TCP $port (Private networks)." -ForegroundColor Green
}

Write-Host ''
Write-Host 'Use one of these URLs in the app (Settings -> Server URL):' -ForegroundColor Cyan
Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.PrefixOrigin -eq 'Dhcp' } |
    ForEach-Object { Write-Host ("    http://{0}:{1}    ({2})" -f $_.IPAddress, $port, $_.InterfaceAlias) }
