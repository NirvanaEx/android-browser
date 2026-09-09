param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JavaHome = 'D:\Programms\jdk'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$output = Join-Path $repo 'build/fenix/android-ui'
New-Item -ItemType Directory -Force -Path "$output/classes" | Out-Null
$platform = "$AndroidSdk/platforms/android-35"
$classpath = "$platform/android.jar"
& "$JavaHome/bin/javac.exe" -source 8 -target 8 -classpath $classpath -d "$output/classes" "$PSScriptRoot/Snapshot.java" "$PSScriptRoot/Touch.java"
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
& "$JavaHome/bin/java.exe" -cp "$AndroidSdk/build-tools/35.0.0/lib/d8.jar" com.android.tools.r8.D8 --lib "$platform/android.jar" --min-api 26 --output "$output/snapshot.jar" "$output/classes/com/upgrid/uitest/Snapshot.class" "$output/classes/com/upgrid/uitest/Touch.class"
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
Write-Output "$output/snapshot.jar"
