# Prints memory of a running Ember (Rust or Kotlin) including its helper processes. Read-only: starts and changes nothing.
# Usage: start the app yourself, leave it idle 30 s, then run:  powershell -File tools\measure-ram.ps1
$names = 'ember-ui', 'Ember', 'xray', 'hev-socks5-tunnel', 'sing-box', 'java', 'javaw'
$rows = Get-Process | Where-Object { $names -contains $_.ProcessName -and ($_.ProcessName -notmatch '^java' -or $_.MainWindowTitle -match 'Ember') }
$rows | Select-Object ProcessName, Id, @{n='WorkingSet_MB'; e={[math]::Round($_.WorkingSet64/1MB,1)}}, @{n='Private_MB'; e={[math]::Round($_.PrivateMemorySize64/1MB,1)}} | Format-Table -AutoSize
'Total working set: {0} MB   total private: {1} MB' -f [math]::Round(($rows | Measure-Object WorkingSet64 -Sum).Sum/1MB,1), [math]::Round(($rows | Measure-Object PrivateMemorySize64 -Sum).Sum/1MB,1)
