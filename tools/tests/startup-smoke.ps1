param(
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Serial = 'emulator-5554',
    [ValidateRange(1, 10)][int]$Attempts = 3,
    [string]$ExpectedUrl,
    [string]$BrowserTabTitle,
    [switch]$CheckMenu,
    [switch]$AllowSlowEmulatorLaunch,
    [switch]$RootedEmulator,
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
        $dump = & $Adb -s $Serial shell uiautomator dump /sdcard/upgrid-startup-smoke.xml 2>&1
        if ($LASTEXITCODE -ne 0 -or "$dump" -notmatch 'dumped to:') { throw 'Cannot inspect current startup UI.' }
        & $Adb -s $Serial pull /sdcard/upgrid-startup-smoke.xml "$output/ui-$attempt.xml" 2>&1 | Out-Null
        [xml]$ui = Get-Content "$output/ui-$attempt.xml"
        $labels = ($ui.SelectNodes('//node') | ForEach-Object { $_.text; $_.GetAttribute('content-desc') }) -join "`n"
        if ($labels -match '(?i)crashed|send a report|isn.t responding') { throw "Crash/ANR dialog on launch $attempt." }
        if ($labels -notmatch 'Search or enter address|Non-private Tabs Open:') { throw "Browser UI missing on launch $attempt." }
        if ($BrowserTabTitle) {
            $counter = $ui.SelectNodes('//node') | Where-Object { $_.GetAttribute('content-desc') -match '^Non-private Tabs Open:' } | Select-Object -First 1
            if (!$counter -or $counter.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Cannot locate tab counter.' }
            & $Adb -s $Serial shell input tap ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) ([int](([int]$Matches[2] + [int]$Matches[4]) / 2))
            Start-Sleep -Seconds 1
            $dump = & $Adb -s $Serial shell uiautomator dump /sdcard/upgrid-tabs-smoke.xml 2>&1
            if ($LASTEXITCODE -ne 0 -or "$dump" -notmatch 'dumped to:') { throw 'Cannot inspect tabs tray.' }
            & $Adb -s $Serial pull /sdcard/upgrid-tabs-smoke.xml "$output/tabs-$attempt.xml" 2>&1 | Out-Null
            [xml]$tabsUi = Get-Content "$output/tabs-$attempt.xml"
            $tab = $tabsUi.SelectNodes('//node') | Where-Object { $_.text -eq $BrowserTabTitle } | Select-Object -First 1
            if (!$tab -or $tab.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw "Saved tab $BrowserTabTitle is not in the tray." }
            & $Adb -s $Serial shell input tap ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) ([int](([int]$Matches[2] + [int]$Matches[4]) / 2))
            Start-Sleep -Seconds 3
            $dump = & $Adb -s $Serial shell uiautomator dump /sdcard/upgrid-browser-smoke.xml 2>&1
            if ($LASTEXITCODE -ne 0 -or "$dump" -notmatch 'dumped to:') { throw 'Cannot inspect restored browser page.' }
            & $Adb -s $Serial pull /sdcard/upgrid-browser-smoke.xml "$output/browser-$attempt.xml" 2>&1 | Out-Null
            $ui = [xml](Get-Content "$output/browser-$attempt.xml")
            if (!$ui.SelectSingleNode('//node[@content-desc="Open video in player"]')) { throw 'Browser toolbar missing on restored page.' }
        }
        if ($CheckMenu) {
            $menu = $ui.SelectSingleNode('//node[@content-desc="Menu" or @content-desc="More options"]')
            if (!$menu -or $menu.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Cannot locate menu button.' }
            $menuX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            $menuY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
            & $Adb -s $Serial shell input tap $menuX $menuY
            Start-Sleep -Seconds 3
            $dump = & $Adb -s $Serial shell uiautomator dump /sdcard/upgrid-menu-smoke.xml 2>&1
            if ($LASTEXITCODE -ne 0 -or "$dump" -notmatch 'dumped to:') { throw 'Cannot inspect opened menu.' }
            & $Adb -s $Serial pull /sdcard/upgrid-menu-smoke.xml "$output/menu-$attempt.xml" 2>&1 | Out-Null
            [xml]$menuUi = Get-Content "$output/menu-$attempt.xml"
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
