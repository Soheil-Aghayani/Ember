# Run by the Ember installer (elevated) so connecting never needs a Windows administrator prompt.
# It registers scheduled tasks that start Ember's network programs with the highest privileges for the user who
# installs. The definitions must match the ones the app checks for (PrivilegedTask.kt, OpenVpn.kt, AppRouting.kt).
param(
    [Parameter(Mandatory = $true)][string]$AppDir,
    [Parameter(Mandatory = $true)][string]$DataDir
)
$ErrorActionPreference = 'Stop'
$user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$resources = Join-Path $AppDir 'app\resources'
$bad = [char[]]@([char]39, [char]34)
foreach ($value in @($AppDir, $DataDir, $user)) { if ($value.IndexOfAny($bad) -ge 0) { exit 0 } }

function Register($name, $exe, $arguments, $workDir, $logon) {
    $action = if ($workDir) { New-ScheduledTaskAction -Execute $exe -Argument $arguments -WorkingDirectory $workDir }
              else { New-ScheduledTaskAction -Execute $exe -Argument $arguments }
    $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType $logon -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)
    Register-ScheduledTask -TaskName $name -TaskPath '\Ember\' -Action $action -Principal $principal -Settings $settings -Force | Out-Null
}

# Whole-device tunnel helper
$helper = Join-Path $resources 'ember-helper.exe'
if (Test-Path $helper) {
    $config = Join-Path $DataDir 'tunnel-state\config.json'
    Register 'Tunnel Up' $helper ("up --config `"$config`"") $null 'Interactive'
    Register 'Tunnel Restore' $helper ("restore --config `"$config`"") $null 'Interactive'
}

# Per-program rules (sing-box). No logon session on screen, so no console window.
$singBox = Get-ChildItem -Path (Join-Path $resources 'tunnel') -Recurse -Filter 'sing-box.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($singBox) {
    $apps = Join-Path $DataDir 'apps'
    New-Item -ItemType Directory -Force -Path $apps | Out-Null
    Register 'Apps' $singBox.FullName ("run -c `"$(Join-Path $apps 'config.json')`" -D `"$apps`"") $apps 'S4U'
}

# OpenVPN, when it is already installed (otherwise the app registers this task the first time it is needed)
$openVpn = Join-Path $env:ProgramFiles 'OpenVPN\bin\openvpn.exe'
if (Test-Path $openVpn) {
    $dir = Join-Path $DataDir 'openvpn'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $arguments = "--config `"$(Join-Path $dir 'active.ovpn')`" --script-security 1 --management 127.0.0.1 17505 `"$(Join-Path $dir 'management.pw')`" --management-hold --management-query-passwords --log `"$(Join-Path $dir 'openvpn.log')`" --verb 3"
    Register 'OpenVPN' $openVpn $arguments $dir 'S4U'
}
