param(
    [ValidateSet('menu', 'status', 'start', 'build', 'watch', 'install', 'open', 'restart', 'capture', 'save', 'restore', 'stop')]
    [string]$Action = 'menu',
    [string]$Apk,
    [string]$Page = 'lab',
    [string]$Note = '',
    [string]$Distro = 'Ubuntu'
)
$ErrorActionPreference = 'Stop'
$python = Join-Path $env:LOCALAPPDATA 'Programs/Python/Python311/python.exe'
if (!(Test-Path -LiteralPath $python)) { $python = (Get-Command python -ErrorAction Stop).Source }
$lab = Join-Path $PSScriptRoot 'lab.py'
function Invoke-Lab([string]$SelectedAction) {
    $labArgs = @($lab, $SelectedAction, '--distro', $Distro, '--page', $Page)
    if ($Note) { $labArgs += @('--note', $Note) }
    if ($Apk) { $labArgs += @('--apk', $Apk) }
    & $python @labArgs
}
if ($Action -ne 'menu') {
    Invoke-Lab $Action
    exit $LASTEXITCODE
}
while ($true) {
    Write-Host "`nUpgrid — Android на ПК" -ForegroundColor Cyan
    Write-Host '1. Открыть Upgrid на тестовом устройстве'
    Write-Host '2. Собрать текущий код и запустить'
    Write-Host '3. Следить за кодом: пересобирать и обновлять (Ctrl+C — остановить)'
    Write-Host '4. Открыть список тестовых страниц'
    Write-Host '5. Сохранить отчёт о баге (скриншот, логи, версия)'
    Write-Host '6. Холодный запуск браузера'
    Write-Host '7. Состояние устройства и сборщика'
    Write-Host '8. Закрыть тестовый Android с сохранением данных'
    Write-Host '0. Закрыть меню'
    $choice = Read-Host 'Действие'
    if ($choice -eq '0') { break }
    $actions = @{ '1'='start'; '2'='build'; '3'='watch'; '4'='open'; '5'='capture'; '6'='restart'; '7'='status'; '8'='stop' }
    if ($actions.ContainsKey($choice)) { Invoke-Lab $actions[$choice] }
}
