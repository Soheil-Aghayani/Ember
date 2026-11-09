# Collects everything the installer packs into one folder. It only copies files: it starts no program and changes no
# network setting.
#   powershell -File stage.ps1 [-Resources "C:\Program Files\Ember\app\resources"] [-Out stage]
# -Resources is the folder with ember-helper.exe, ember-tunnel.ps1 and tunnel\ (an installed Kotlin Ember has one).
param(
    [string]$Resources = 'C:\Program Files\Ember\app\resources',
    [string]$Out = (Join-Path $PSScriptRoot 'stage')
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$exe = Join-Path $root 'target\release\ember-ui.exe'
if (-not (Test-Path $exe)) { throw "Build first: cargo build --release -p ember-ui ($exe is missing)" }
foreach ($need in 'ember-helper.exe', 'ember-tunnel.ps1', 'tunnel') {
    if (-not (Test-Path (Join-Path $Resources $need))) { throw "$need is missing from $Resources" }
}
if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
New-Item -ItemType Directory -Path $Out | Out-Null
# one program in the folder; everything it needs sits in resources\
Copy-Item $exe (Join-Path $Out 'Ember.exe')
$inside = Join-Path $Out 'resources'
New-Item -ItemType Directory -Path $inside | Out-Null
Copy-Item (Join-Path $root 'ember-ui\ui\assets\flags') (Join-Path $inside 'flags') -Recurse
foreach ($need in 'ember-helper.exe', 'ember-tunnel.ps1', 'tunnel') { Copy-Item (Join-Path $Resources $need) $inside -Recurse }
$size = (Get-ChildItem $Out -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
'Staged {0:N0} MB in {1}' -f $size, $Out
$iscc = (Get-Command ISCC -ErrorAction SilentlyContinue).Source
if (-not $iscc) { $iscc = @('C:\Program Files (x86)\Inno Setup 6\ISCC.exe', 'C:\Program Files\Inno Setup 6\ISCC.exe') | Where-Object { Test-Path $_ } | Select-Object -First 1 }
if ($iscc) { "Build the installer:  & '$iscc' /DSourceDir='$Out' /DOutputDir='$(Join-Path $PSScriptRoot 'Output')' '$(Join-Path $PSScriptRoot 'ember-rs.iss')'" }
else { 'Inno Setup 6 is not installed; install it (jrsoftware.org), then run ISCC on ember-rs.iss with /DSourceDir pointing at the stage folder.' }
