# Ember whole-device tunnel helper (Windows). Runs elevated.
#
#   -Mode Up       start hev-socks5-tunnel on a Wintun adapter, route traffic into it, apply the kill switch,
#                  then watch until Ember asks it to stop.
#   -Mode Restore  undo everything Up did (also used to recover after a crash with the kill switch on).
#
# Security: this script is run elevated from the application folder. It never executes anything named in
# the config file; the config only supplies validated data (addresses, numbers, a state folder).

param(
    [Parameter(Mandatory = $true)][ValidateSet('Up', 'Restore')][string]$Mode,
    [Parameter(Mandatory = $true)][string]$ConfigPath
)

$ErrorActionPreference = 'Stop'

# Log and report even if the very first steps fail (the config lives in the state folder).
$bootDir = Split-Path -Parent $ConfigPath
function Boot-Log([string]$m) {
    try { Add-Content -LiteralPath (Join-Path $bootDir 'helper.log') -Value ("{0} {1}" -f (Get-Date -Format s), $m) -Encoding ascii } catch { }
}
Boot-Log ("helper started: mode=$Mode user=$([Environment]::UserName) ps=$($PSVersionTable.PSVersion)")
trap {
    Boot-Log ("fatal: " + $_.Exception.Message + " at line " + $_.InvocationInfo.ScriptLineNumber)
    try { Set-Content -LiteralPath (Join-Path $bootDir 'status') -Value ('ERROR: ' + $_.Exception.Message) -Encoding ascii } catch { }
    exit 1
}

Set-StrictMode -Version Latest

$RuleGroup = 'Ember'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Test-Ipv4([string]$value) {
    if ($value -notmatch '^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$') { return $false }
    foreach ($i in 1..4) { if ([int]$Matches[$i] -gt 255) { return $false } }
    return $true
}

function ConvertTo-Long([string]$ip) {
    $b = [System.Net.IPAddress]::Parse($ip).GetAddressBytes()
    return [long]$b[0] * 16777216 + [long]$b[1] * 65536 + [long]$b[2] * 256 + [long]$b[3]
}

function ConvertTo-Ip([long]$n) {
    return ('{0}.{1}.{2}.{3}' -f (($n -shr 24) -band 255), (($n -shr 16) -band 255), (($n -shr 8) -band 255), ($n -band 255))
}

function Get-CidrRange([string]$cidr) {
    $parts = $cidr.Split('/')
    $start = ConvertTo-Long $parts[0]
    $size = [long][math]::Pow(2, 32 - [int]$parts[1])
    return @($start, ($start + $size - 1))
}

# The IPv4 space minus the given [start,end] intervals, as 'a.b.c.d-e.f.g.h' ranges for the firewall.
function Get-ComplementRanges($intervals) {
    $sorted = @($intervals | Sort-Object { $_[0] })
    $result = New-Object System.Collections.ArrayList
    $next = [long]0
    foreach ($iv in $sorted) {
        if ($iv[0] -gt $next) { [void]$result.Add((ConvertTo-Ip $next) + '-' + (ConvertTo-Ip ($iv[0] - 1))) }
        if ($iv[1] + 1 -gt $next) { $next = $iv[1] + 1 }
    }
    if ($next -le 4294967295) { [void]$result.Add((ConvertTo-Ip $next) + '-255.255.255.255') }
    return , @($result)
}

$cfg = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json

$stateDir = [string]$cfg.stateDir
if (-not (Test-Path -LiteralPath $stateDir -PathType Container)) { throw 'stateDir is not a folder' }
$statusFile = Join-Path $stateDir 'status'
$stopFile = Join-Path $stateDir 'stop'
$routesFile = Join-Path $stateDir 'routes.json'
$logFile = Join-Path $stateDir 'helper.log'

function Write-State([string]$text) {
    Set-Content -LiteralPath $statusFile -Value $text -Encoding ascii
}

function Write-Log([string]$text) {
    Add-Content -LiteralPath $logFile -Value ("{0} {1}" -f (Get-Date -Format s), $text) -Encoding ascii
}

function Remove-AddedRoutes {
    if (-not (Test-Path -LiteralPath $routesFile)) { return }
    $saved = @(Get-Content -LiteralPath $routesFile -Raw | ConvertFrom-Json)
    foreach ($r in $saved) {
        $prefix = [string]$r.prefix
        $idx = [int]$r.ifIndex
        if ($prefix -notmatch '^\d{1,3}(\.\d{1,3}){3}/\d{1,2}$') { continue }
        Remove-NetRoute -DestinationPrefix $prefix -InterfaceIndex $idx -PolicyStore ActiveStore -Confirm:$false -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $routesFile -ErrorAction SilentlyContinue
}

function Invoke-Cleanup {
    $ErrorActionPreference = 'Continue'
    try { Remove-AddedRoutes } catch { Write-Log ('cleanup (routes): ' + $_.Exception.Message) }
    try { Remove-NetFirewallRule -Group $RuleGroup -PolicyStore ActiveStore -ErrorAction SilentlyContinue } catch { Write-Log ('cleanup (firewall): ' + $_.Exception.Message) }
    try { Get-Process -Name 'hev-socks5-tunnel' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue } catch { }
}

if ($Mode -eq 'Restore') {
    Invoke-Cleanup
    Write-State 'DOWN'
    Write-Log 'restore complete'
    exit 0
}

# ---------------------------------------------------------------- Up

$adapterName = [string]$cfg.adapterName
if ($adapterName -notmatch '^[A-Za-z0-9_-]{1,32}$') { throw 'invalid adapterName' }
$tunAddress = [string]$cfg.tunAddress
$mapdns = [string]$cfg.mapdnsAddress
$hevConfig = [string]$cfg.hevConfigPath
$killSwitch = [bool]$cfg.killSwitch
$parentPid = [int]$cfg.parentPid
$edgeIps = @($cfg.edgeIps)
foreach ($ip in @($tunAddress, $mapdns) + $edgeIps) {
    if (-not (Test-Ipv4 ([string]$ip))) { throw "invalid address in config: $ip" }
}
if (-not (Test-Path -LiteralPath $hevConfig -PathType Leaf)) { throw 'hev config not found' }
if ((Split-Path -Parent $hevConfig) -ne $stateDir -and (Split-Path -Parent $hevConfig) -ne (Split-Path -Parent $stateDir)) {
    # hev config must live in the Ember data folder (state folder or its parent)
    throw 'hev config is outside the Ember data folder'
}

$hev = Get-ChildItem -LiteralPath (Join-Path $here 'tunnel') -Recurse -Filter 'hev-socks5-tunnel.exe' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $hev) { throw 'hev-socks5-tunnel.exe was not found next to this script' }

$added = New-Object System.Collections.ArrayList
function Add-Route([string]$prefix, [int]$ifIndex, [string]$nextHop, [int]$metric) {
    New-NetRoute -DestinationPrefix $prefix -InterfaceIndex $ifIndex -NextHop $nextHop -RouteMetric $metric `
        -PolicyStore ActiveStore -ErrorAction Stop | Out-Null
    [void]$added.Add(@{ prefix = $prefix; ifIndex = $ifIndex })
    ConvertTo-Json -InputObject @($added) | Set-Content -LiteralPath $routesFile -Encoding ascii
}

Remove-Item -LiteralPath $stopFile -ErrorAction SilentlyContinue
Write-State 'STARTING'
Write-Log "up requested (killSwitch=$killSwitch)"

$proc = $null
try {
    # The physical default route, found before anything is changed.
    $def = Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop |
        Where-Object { $_.NextHop -ne '0.0.0.0' } |
        Sort-Object RouteMetric |
        Select-Object -First 1
    if (-not $def) { throw 'no default IPv4 route found' }

    # Kill switch first: if anything after this fails or the tunnel dies, traffic is dropped, not leaked.
    if ($killSwitch) {
        # Block public IPv4 on every active adapter except the tunnel. While the tunnel is up, the default
        # routes send public traffic into it, so only Mozilla's edge and local-network traffic use these
        # adapters. If the tunnel disappears, traffic falls back to them and is dropped instead of leaking.
        $aliases = @(Get-NetAdapter | Where-Object { $_.Status -eq 'Up' -and $_.Name -ne $adapterName } | ForEach-Object { $_.Name })
        if ($aliases.Count -eq 0) { throw 'no active network adapter was found for the kill switch' }
        $allowed = New-Object System.Collections.ArrayList
        foreach ($cidr in @('0.0.0.0/8', '10.0.0.0/8', '127.0.0.0/8', '169.254.0.0/16', '172.16.0.0/12', '192.168.0.0/16')) {
            [void]$allowed.Add((Get-CidrRange $cidr))
        }
        [void]$allowed.Add(@([long]3758096384, [long]4294967295))
        foreach ($ip in $edgeIps) { $n = ConvertTo-Long ([string]$ip); [void]$allowed.Add(@($n, $n)) }
        $blockRanges = Get-ComplementRanges $allowed
        New-NetFirewallRule -DisplayName 'Ember kill switch' -Group $RuleGroup -Direction Outbound -Action Block `
            -InterfaceAlias $aliases -RemoteAddress $blockRanges -PolicyStore ActiveStore | Out-Null
        Write-Log ('kill switch rule added on: ' + ($aliases -join ', ') + ' (' + $blockRanges.Count + ' blocked ranges)')
    }

    # The route to Mozilla's edge must stay on the physical network or the tunnel would loop into itself.
    foreach ($ip in $edgeIps) { Add-Route "$ip/32" $def.InterfaceIndex $def.NextHop 1 }

    $proc = Start-Process -FilePath $hev.FullName -ArgumentList ('"' + $hevConfig + '"') `
        -WorkingDirectory $hev.DirectoryName -WindowStyle Hidden -PassThru

    $tun = $null
    for ($i = 0; $i -lt 40; $i++) {
        if ($proc.HasExited) { throw 'the tunnel process exited during start-up' }
        $tun = Get-NetAdapter -Name $adapterName -ErrorAction SilentlyContinue
        if ($tun) { break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $tun) { throw "the tunnel adapter '$adapterName' did not appear" }
    $idx = [int]$tun.ifIndex

    $hasAddress = Get-NetIPAddress -InterfaceIndex $idx -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -eq $tunAddress }
    if (-not $hasAddress) {
        New-NetIPAddress -InterfaceIndex $idx -IPAddress $tunAddress -PrefixLength 30 -PolicyStore ActiveStore | Out-Null
    }
    Set-NetIPInterface -InterfaceIndex $idx -InterfaceMetric 1 -AutomaticMetric Disabled
    Set-DnsClientServerAddress -InterfaceIndex $idx -ServerAddresses @($mapdns)

    Add-Route '0.0.0.0/1' $idx '0.0.0.0' 1
    Add-Route '128.0.0.0/1' $idx '0.0.0.0' 1

    # DNS leak guard: only the tunnel's resolver may be asked.
    New-NetFirewallRule -DisplayName 'Ember DNS guard (UDP)' -Group $RuleGroup -Direction Outbound -Action Block `
        -Protocol UDP -RemotePort 53 -RemoteAddress @('0.0.0.0-198.18.0.1', '198.18.0.3-255.255.255.255') `
        -PolicyStore ActiveStore | Out-Null
    New-NetFirewallRule -DisplayName 'Ember DNS guard (TCP)' -Group $RuleGroup -Direction Outbound -Action Block `
        -Protocol TCP -RemotePort 53 -RemoteAddress @('0.0.0.0-198.18.0.1', '198.18.0.3-255.255.255.255') `
        -PolicyStore ActiveStore | Out-Null

    # IPv6 is not tunnelled, so public IPv6 is blocked to stop it bypassing the VPN.
    New-NetFirewallRule -DisplayName 'Ember IPv6 guard' -Group $RuleGroup -Direction Outbound -Action Block `
        -RemoteAddress '2000::-3fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff' -PolicyStore ActiveStore | Out-Null

    Write-State 'UP'
    Write-Log 'tunnel is up'

    while ($true) {
        Start-Sleep -Milliseconds 1000
        if (Test-Path -LiteralPath $stopFile) {
            Invoke-Cleanup
            Write-State 'DOWN'
            Write-Log 'stopped on request'
            break
        }
        if ($proc.HasExited) {
            if (-not $killSwitch) {
                Invoke-Cleanup
                Write-State 'ERROR: the tunnel process exited'
                break
            }
            Write-State 'BLOCKED: the tunnel process exited; the kill switch is blocking traffic'
            Write-Log 'tunnel process exited; kill switch holding'
            while (-not (Test-Path -LiteralPath $stopFile)) { Start-Sleep -Milliseconds 1000 }
            Invoke-Cleanup
            Write-State 'DOWN'
            break
        }
        if (-not (Get-Process -Id $parentPid -ErrorAction SilentlyContinue)) {
            Get-Process -Name 'hev-socks5-tunnel' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
            if ($killSwitch) {
                Write-State 'BLOCKED: Ember closed unexpectedly; the kill switch is blocking traffic'
                Write-Log 'parent gone; kill switch left in place'
            } else {
                Invoke-Cleanup
                Write-State 'DOWN'
            }
            break
        }
    }
} catch {
    $failure = $_.Exception.Message
    Write-Log ('error: ' + $failure)
    Invoke-Cleanup
    Write-State ('ERROR: ' + $failure)
    exit 1
}
