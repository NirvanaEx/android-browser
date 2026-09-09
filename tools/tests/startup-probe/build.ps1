param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JavaHome = 'D:\Programms\jdk',
    [Parameter(Mandatory = $true)][string]$DebugKeystore
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$output = Join-Path $repo 'build/fenix/startup-probe'
$classes = Join-Path $output 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$androidJar = Join-Path $AndroidSdk 'platforms/android-35/android.jar'
$buildTools = Join-Path $AndroidSdk 'build-tools/35.0.0'
& "$JavaHome/bin/javac.exe" -source 8 -target 8 -classpath $androidJar -d $classes "$PSScriptRoot/StartupProbe.java" "$PSScriptRoot/DiagnosticsProbe.java"
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
$classFiles = Get-ChildItem -LiteralPath "$classes/com/upgrid/startupprobe" -Filter '*.class' | ForEach-Object { $_.FullName }
& "$JavaHome/bin/java.exe" -cp "$buildTools/lib/d8.jar" com.android.tools.r8.D8 --lib $androidJar --min-api 26 --output $output @classFiles
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
$unsigned = Join-Path $output 'unsigned.apk'
& "$buildTools/aapt2.exe" link --manifest "$PSScriptRoot/AndroidManifest.xml" -I $androidJar -o $unsigned
if ($LASTEXITCODE -ne 0) { throw 'aapt2 failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open($unsigned, 'Update')
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$output/classes.dex", 'classes.dex') | Out-Null } finally { $zip.Dispose() }
& "$buildTools/zipalign.exe" -f 4 $unsigned "$output/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
& "$JavaHome/bin/java.exe" -jar "$buildTools/lib/apksigner.jar" sign --ks $DebugKeystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$output/startup-probe.apk" "$output/aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
Write-Output "$output/startup-probe.apk"
