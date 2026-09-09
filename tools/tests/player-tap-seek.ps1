param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Serial = 'emulator-5554'
)
# Open native.html, press Pause at 10 seconds, then the browser's player action.
# This exercises real Android touch dispatch and the real Media3 time bar.
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Run this probe only on an emulator.' }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $repo 'build/fenix/tap-seek-regression'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$snapshotJar = Join-Path $repo 'build/fenix/android-ui/snapshot.jar'
if (!(Test-Path $snapshotJar)) { throw 'Build tools/tests/android-ui/build.ps1 first.' }
& $Adb -s $Serial push $snapshotJar /data/local/tmp/upgrid-snapshot.jar 2>&1 | Out-Null
$script:dumpNumber = 0
$results = [System.Collections.Generic.List[object]]::new()
function Get-PlayerUi {
    $script:dumpNumber++
    $dump = & $Adb -s $Serial shell 'CLASSPATH=/data/local/tmp/upgrid-snapshot.jar app_process /system/bin com.upgrid.uitest.Snapshot /data/local/tmp/upgrid-taps.xml' 2>&1
    if ($LASTEXITCODE -ne 0 -or "$dump" -notmatch 'SNAPSHOT_OK') { throw "Cannot inspect Android UI: $dump" }
    $path = Join-Path $output "ui-$script:dumpNumber.xml"
    & $Adb -s $Serial pull /data/local/tmp/upgrid-taps.xml $path 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot fetch Android UI snapshot.' }
    return [xml](Get-Content $path -Raw)
}
function Find-Control($ui, $id) {
    $node = $ui.SelectSingleNode("//node[@resource-id='com.upgrid.browser.next.debug:id/$id']")
    if (!$node) { throw "Native player control not found: $id" }
    return $node
}
function Get-Rect($node) {
    if ($node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Missing control bounds.' }
    return @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
}
function Tap-Half($ui, [bool]$right, [int]$count = 2) {
    $r = Get-Rect (Find-Control $ui 'exo_overlay')
    $x = [int]($r[0] + ($r[2] - $r[0]) * $(if ($right) { 0.75 } else { 0.25 }))
    $y = [int]($r[1] + ($r[3] - $r[1]) * 0.25)
    & $Adb -s $Serial shell "CLASSPATH=/data/local/tmp/upgrid-snapshot.jar app_process /system/bin com.upgrid.uitest.Touch $x $y $count"
    if ($LASTEXITCODE -ne 0) { throw 'Touch injection failed.' }
}
function Tap-Control($ui, $id) {
    $r = Get-Rect (Find-Control $ui $id)
    & $Adb -s $Serial shell input tap ([int](($r[0]+$r[2])/2)) ([int](($r[1]+$r[3])/2))
}
function Assert-Position($label, [int]$expected, [int]$tolerance = 0) {
    $ui = Get-PlayerUi
    $value = (Find-Control $ui 'exo_position').text
    $parts = @($value.Split(':') | ForEach-Object { [int]$_ })
    $seconds = 0
    foreach ($part in $parts) { $seconds = $seconds * 60 + $part }
    if ([Math]::Abs($seconds - $expected) -gt $tolerance) { throw "$label expected $expected seconds, got $seconds" }
    if ((Find-Control $ui 'exo_play_pause').'content-desc' -ne 'Play') { throw "$label changed pause state" }
    $results.Add(@{ check=$label; seconds=$seconds; paused=$true })
    Write-Host "PASS $label ($seconds s, paused)"
    return $ui
}
$ui = Assert-Position 'initial paused frame' 10
foreach ($id in @('exo_ffwd_with_amount','exo_rew_with_amount')) {
    if ($ui.SelectSingleNode("//node[@resource-id='com.upgrid.browser.next.debug:id/$id']")) { throw "Unexpected seek button: $id" }
}
Tap-Half $ui $true 1
# A single tap may hide controls. Another separated single tap reveals them.
Tap-Half $ui $true 1
$ui = Assert-Position 'single taps do not seek' 10
Tap-Half $ui $true
$ui = Assert-Position 'double right tap adds five seconds' 15
Tap-Half $ui $false
$ui = Assert-Position 'double left tap subtracts five seconds' 10
for ($tap = 0; $tap -lt 3; $tap++) { Tap-Half $ui $true }
$ui = Assert-Position 'three double taps are counted once each' 25
Tap-Half $ui $true
Tap-Half $ui $true
$ui = Assert-Position 'forward clamps at duration' 30
for ($tap = 0; $tap -lt 8; $tap++) { Tap-Half $ui $false }
$ui = Assert-Position 'backward clamps at zero' 0
Tap-Half $ui $true
Tap-Half $ui $true
$ui = Assert-Position 'restore middle position' 10
$r = Get-Rect (Find-Control $ui 'exo_overlay')
$x1 = [int]($r[0]+($r[2]-$r[0])*0.25)
$x2 = [int]($r[0]+($r[2]-$r[0])*0.75)
$y = [int]($r[1]+($r[3]-$r[1])*0.25)
& $Adb -s $Serial shell input swipe $x1 $y $x2 $y 350
$ui = Assert-Position 'swipe is not a tap' 10
& $Adb -s $Serial shell input swipe $x2 $y $x2 $y 800
$ui = Assert-Position 'hold is not a tap' 10
$r = Get-Rect (Find-Control $ui 'exo_progress')
$y = [int](($r[1]+$r[3])/2)
& $Adb -s $Serial shell input swipe ([int]($r[0]+($r[2]-$r[0])/3)) $y ([int]($r[0]+($r[2]-$r[0])*2/3)) $y 600
$ui = Assert-Position 'time-bar scrub has no extra five-second jump' 20 1
$results | ConvertTo-Json | Set-Content (Join-Path $output 'results.json') -Encoding utf8
