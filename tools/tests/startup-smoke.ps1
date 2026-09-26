param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Serial = 'emulator-5554',
    [ValidateRange(1, 10)][int]$Attempts = 3,
    [string]$ExpectedUrl,
    [string]$BrowserTabTitle,
    [switch]$CheckMenu,
    [switch]$AllowSlowEmulatorLaunch,
    [switch]$RootedEmulator,
    [switch]$UseSnapshotProbe,
    [string]$OutputName = 'startup-smoke'
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'This test only operates on an emulator.' }
if ($RootedEmulator -and ((& $Adb -s $Serial shell id -u).Trim() -ne '0')) {
    throw 'RootedEmulator requires adb root on the test emulator.'
}
if ($OutputName -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Use a simple output directory name.' }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $repo "build/fenix/$OutputName"
New-Item -ItemType Directory -Force -Path $output | Out-Null
$package = 'com.upgrid.browser.next.debug'
if ($UseSnapshotProbe) {
    $snapshotJar = Join-Path $repo 'build/fenix/android-ui/snapshot.jar'
    if (!(Test-Path $snapshotJar)) { throw 'Build tools/tests/android-ui/build.ps1 first.' }
    & $Adb -s $Serial push $snapshotJar /data/local/tmp/upgrid-snapshot.jar 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot install UI snapshot probe.' }
}
function Invoke-SmokeAdb([string[]]$arguments, [int]$timeoutMilliseconds = 15000) {
    if ($timeoutMilliseconds -le 0) { throw 'UI inspection deadline expired.' }
    $command = [System.Diagnostics.Process]::new()
    $command.StartInfo.FileName = $Adb
    $command.StartInfo.UseShellExecute = $false
    $command.StartInfo.CreateNoWindow = $true
    $command.StartInfo.RedirectStandardOutput = $true
    $command.StartInfo.RedirectStandardError = $true
    foreach ($argument in (@('-s', $Serial) + $arguments)) { $command.StartInfo.ArgumentList.Add($argument) }
    try {
        [void]$command.Start()
        $stdout = $command.StandardOutput.ReadToEndAsync()
        $stderr = $command.StandardError.ReadToEndAsync()
        if (!$command.WaitForExit($timeoutMilliseconds)) {
            # Stop only this owned adb client, never the server or the application.
            $command.Kill()
            throw "UI command exceeded ${timeoutMilliseconds}ms: $($arguments -join ' ')"
        }
        $result = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        if ($command.ExitCode -ne 0) { throw "UI command failed: $result" }
        return $result
    } finally {
        $command.Dispose()
    }
}
function Invoke-SmokeTap([int]$x, [int]$y) {
    if ($UseSnapshotProbe) {
        # One real DOWN/UP pair with the helper's 35ms hold; never retry the action.
        $touch = Invoke-SmokeAdb @('shell', "CLASSPATH=/data/local/tmp/upgrid-snapshot.jar app_process /system/bin com.upgrid.uitest.Touch $x $y 1")
        if ($touch -notmatch 'TOUCH_OK') { throw "Touch injection failed: $touch" }
    } else {
        Invoke-SmokeAdb @('shell', 'input', 'tap', "$x", "$y") | Out-Null
    }
}
function Save-UiSnapshot([string]$remotePath, [string]$localPath, [int]$timeoutMilliseconds = 15000) {
    $snapshotClock = [System.Diagnostics.Stopwatch]::StartNew()
    if ($UseSnapshotProbe) {
        # Uses the same Android accessibility tree without waiting for an idle animation loop.
        $dump = Invoke-SmokeAdb @('shell', "CLASSPATH=/data/local/tmp/upgrid-snapshot.jar app_process /system/bin com.upgrid.uitest.Snapshot $remotePath 1500") $timeoutMilliseconds
        if ($dump -notmatch 'SNAPSHOT_OK') { throw "Cannot inspect UI: $dump" }
    } else {
        $dump = Invoke-SmokeAdb @('shell', 'uiautomator', 'dump', $remotePath) $timeoutMilliseconds
        if ($dump -notmatch 'dumped to:') { throw "Cannot inspect UI: $dump" }
    }
    $remaining = $timeoutMilliseconds - [int]$snapshotClock.ElapsedMilliseconds
    Invoke-SmokeAdb @('pull', $remotePath, $localPath) $remaining | Out-Null
}
function Wait-UiMarker([string]$remotePath, [string]$localPath, [string]$markerXPath, [string]$description, [int]$timeoutMilliseconds = 15000) {
    $waitClock = [System.Diagnostics.Stopwatch]::StartNew()
    while ($waitClock.ElapsedMilliseconds -lt $timeoutMilliseconds) {
        $remaining = $timeoutMilliseconds - [int]$waitClock.ElapsedMilliseconds
        Save-UiSnapshot $remotePath $localPath $remaining
        [xml]$markerUi = Get-Content -LiteralPath $localPath -Raw
        $markerLabels = ($markerUi.SelectNodes('//node') | ForEach-Object { $_.text; $_.GetAttribute('content-desc') }) -join "`n"
        if ($markerLabels -match '(?i)crashed|send a report|isn.t responding') { throw "Crash/ANR dialog while waiting for $description." }
        if ($waitClock.ElapsedMilliseconds -ge $timeoutMilliseconds) { break }
        if ($markerUi.SelectSingleNode($markerXPath)) { return ,$markerUi }
        $remaining = $timeoutMilliseconds - [int]$waitClock.ElapsedMilliseconds
        if ($remaining -gt 0) { Start-Sleep -Milliseconds ([Math]::Min(250, $remaining)) }
    }
    throw "Timed out after ${timeoutMilliseconds}ms waiting for $description; last snapshot: $localPath"
}
for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
    & $Adb -s $Serial shell am force-stop $package | Out-Null
    & $Adb -s $Serial logcat -c
    if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare emulator logcat.' }
    try {
        $launch = & $Adb -s $Serial shell am start -W -n "$package/org.mozilla.fenix.HomeActivity" 2>&1
        $launch | Set-Content "$output/launch-$attempt.txt"
        $launchTimedOut = "$launch" -match 'Status: timeout'
        if ($LASTEXITCODE -ne 0 -or ("$launch" -notmatch 'Status: ok' -and !($AllowSlowEmulatorLaunch -and $launchTimedOut))) {
            throw "Launch $attempt failed: $launch"
        }
        if ($launchTimedOut) {
            Write-Warning 'Emulator exceeded am start frame deadline; requiring stable PID and fully resumed browser UI below. This is not a startup-speed pass.'
        }
        $initialPid = (& $Adb -s $Serial shell pidof $package).Trim()
        if (!$initialPid) { throw "Launch $attempt has no application process." }
        for ($sample = 1; $sample -le 6; $sample++) {
            Start-Sleep -Seconds 5
            $appPid = & $Adb -s $Serial shell pidof $package
            if (!$appPid -or $appPid.Trim() -ne $initialPid) { throw "Application exited or restarted on launch $attempt." }
        }
        $activities = & $Adb -s $Serial shell dumpsys activity activities
        $activities | Set-Content "$output/activities-$attempt.txt"
        if (($activities -join "`n") -notmatch '(?:ResumedActivity|topResumedActivity).*org.mozilla.fenix.HomeActivity') {
            throw "HomeActivity is not in the foreground after launch $attempt."
        }
        Save-UiSnapshot /sdcard/upgrid-startup-smoke.xml "$output/ui-$attempt.xml"
        [xml]$ui = Get-Content "$output/ui-$attempt.xml"
        $labels = ($ui.SelectNodes('//node') | ForEach-Object { $_.text; $_.GetAttribute('content-desc') }) -join "`n"
        if ($labels -match '(?i)crashed|send a report|isn.t responding') { throw "Crash/ANR dialog on launch $attempt." }
        if ($labels -notmatch 'Search or enter address|Non-private Tabs Open:') { throw "Browser UI missing on launch $attempt." }
        if ($BrowserTabTitle) {
            $counter = $ui.SelectNodes('//node') | Where-Object { $_.GetAttribute('content-desc') -match '^Non-private Tabs Open:' } | Select-Object -First 1
            if (!$counter -or $counter.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Cannot locate tab counter.' }
            Invoke-SmokeTap ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) ([int](([int]$Matches[2] + [int]$Matches[4]) / 2))
            # A title in the top tab strip is not evidence that the tray opened.
            [xml]$tabsUi = Wait-UiMarker /sdcard/upgrid-tabs-smoke.xml "$output/tabs-$attempt.xml" '//node[@content-desc="Open tabs menu"]' 'tabs tray'
            $tab = $tabsUi.SelectNodes('//node') | Where-Object { $_.text -eq $BrowserTabTitle } | Select-Object -First 1
            if (!$tab -or $tab.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw "Saved tab $BrowserTabTitle is not in the tray." }
            Invoke-SmokeTap ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) ([int](([int]$Matches[2] + [int]$Matches[4]) / 2))
            $ui = Wait-UiMarker /sdcard/upgrid-browser-smoke.xml "$output/browser-$attempt.xml" '//node[@content-desc="Open video in player"]' 'restored browser toolbar'
        }
        if ($CheckMenu) {
            $menu = $ui.SelectSingleNode('//node[@content-desc="Menu" or @content-desc="More options"]')
            if (!$menu -or $menu.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Cannot locate menu button.' }
            $menuX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            $menuY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
            Invoke-SmokeTap $menuX $menuY
            [xml]$menuUi = Wait-UiMarker /sdcard/upgrid-menu-smoke.xml "$output/menu-$attempt.xml" '//node[@content-desc="History"]' 'application menu'
            foreach ($item in @('History', 'Bookmarks', 'Downloads', 'Settings')) {
                if (!$menuUi.SelectSingleNode("//node[@content-desc='$item']")) { throw "Menu item $item missing." }
            }
            if ($BrowserTabTitle -and !$menuUi.SelectSingleNode('//node[@content-desc="Find in page"]')) { throw 'Expected browser-page menu, got home menu.' }
            $appPid = & $Adb -s $Serial shell pidof $package
            if (!$appPid -or $appPid.Trim() -ne $initialPid) { throw 'Application exited while opening menu.' }
            & $Adb -s $Serial shell input keyevent 4
        }
        $session = if ($RootedEmulator) {
            & $Adb -s $Serial shell cat "/data/user/0/$package/files/mozilla_components_session_storage_gecko.json"
        } else {
            & $Adb -s $Serial shell run-as $package cat files/mozilla_components_session_storage_gecko.json
        }
        if ($LASTEXITCODE -ne 0) { throw 'Cannot read saved sessions; use RootedEmulator for non-debuggable builds.' }
        $session | Set-Content "$output/session-$attempt.json"
        if ($ExpectedUrl -and ($session -join "`n") -notlike "*$ExpectedUrl*") { throw "Saved tab missing on launch $attempt." }
        Write-Output "PASS launch $attempt/$Attempts; PID $initialPid stable for 30 seconds; menu checked=$CheckMenu; expected tab checked=$([bool]$ExpectedUrl)."
    } finally {
        & $Adb -s $Serial logcat -d -v threadtime > "$output/logcat-$attempt.txt"
    }
}
