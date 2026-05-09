<#
.SYNOPSIS
    Resize Android screenshots so Claude Code chats never hit the 2000 px API limit.

.DESCRIPTION
    Anthropic's API rejects multi-image requests when any image exceeds 2000 px on any side.
    Stock Android phones produce 1080x2400 screenshots, which trip this limit and break the
    whole conversation. This script downsizes anything over the limit to fit, in-place.

    Aspect ratio is preserved. PNG format is preserved (sharp UI > smaller filesize for
    visual review). Files already under the limit are skipped.

.PARAMETER Path
    File or directory. Defaults to .\screenshots\ next to this script.

.PARAMETER MaxDim
    Largest allowed side after resize. Defaults to 1900 (small safety margin under 2000).

.EXAMPLE
    .\tools\Resize-Screenshots.ps1
    # Resizes anything in .\screenshots\ that's too big.

.EXAMPLE
    .\tools\Resize-Screenshots.ps1 -Path D:\Downloads\Screenshot_2026-05-09.png
    # Resize a single dropped file in place.
#>
param(
    [string]$Path = (Join-Path $PSScriptRoot "..\screenshots"),
    [int]$MaxDim = 1900
)

Add-Type -AssemblyName System.Drawing

function Resize-OneImage {
    param([string]$File, [int]$Max)

    try {
        $img = [System.Drawing.Image]::FromFile($File)
    } catch {
        Write-Warning "Skip (cannot read): $File"
        return
    }

    $w = $img.Width; $h = $img.Height
    if ($w -le $Max -and $h -le $Max) {
        $img.Dispose()
        Write-Host "OK    ${w}x${h}  $([System.IO.Path]::GetFileName($File))" -ForegroundColor DarkGray
        return
    }

    $scale = [math]::Min($Max / $w, $Max / $h)
    $nw = [int]($w * $scale)
    $nh = [int]($h * $scale)

    $bmp = New-Object System.Drawing.Bitmap($nw, $nh)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode     = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode   = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.DrawImage($img, 0, 0, $nw, $nh)
    $g.Dispose()
    $img.Dispose()

    # Atomic save: write to temp, then replace.
    $tmp = "$File.resize.tmp"
    $bmp.Save($tmp, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Move-Item -Path $tmp -Destination $File -Force

    Write-Host "RESZ  ${w}x${h} -> ${nw}x${nh}  $([System.IO.Path]::GetFileName($File))" -ForegroundColor Green
}

if (-not (Test-Path $Path)) {
    Write-Error "Path not found: $Path"
    exit 1
}

$item = Get-Item $Path
if ($item.PSIsContainer) {
    $files = Get-ChildItem -Path $Path -Include *.png,*.jpg,*.jpeg -Recurse -File
    if (-not $files) {
        Write-Host "No images in $Path" -ForegroundColor Yellow
        exit 0
    }
    foreach ($f in $files) { Resize-OneImage -File $f.FullName -Max $MaxDim }
} else {
    Resize-OneImage -File $item.FullName -Max $MaxDim
}

Write-Host "`nDone. Anything marked RESZ is now safe to drop into a Claude Code chat." -ForegroundColor Cyan
