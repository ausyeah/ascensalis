# Post-process generated badge art into circular transparent PNGs:
#   1. circular mask (radius = MASK_RADIUS_FRAC) - covers the badge disc, cuts the
#      square corners, which is exactly where the model watermark sits
#   2. 2px feathered edge to avoid aliasing
#   3. downsample to TARGET_PX so each file drops from ~1.2MB to ~200KB
#
# Why this is needed: the image model ignores the transparent instruction and emits
# Format24bppRgb (no alpha) squares with opaque light corners plus a bottom-right
# watermark. BadgeImage's contract is "circular transparent PNG, shadow follows
# CircleShape outline".
param(
    [string]$Dir = "$PSScriptRoot\..\app\src\main\res\drawable-nodpi",
    [int]$TargetPx = 512,
    [double]$MaskRadiusFrac = 0.488,
    [switch]$WhatIf
)

Add-Type -AssemblyName System.Drawing

$files = Get-ChildItem -Path $Dir -Filter "badge_*.png" | Sort-Object Name
if ($files.Count -eq 0) {
    Write-Output "no badge_*.png found in $Dir"
    exit 0
}

$center = $TargetPx / 2.0
$radius = $TargetPx * $MaskRadiusFrac
$feather = 2.0

foreach ($f in $files) {
    $src = New-Object System.Drawing.Bitmap $f.FullName
    try {
        $dst = New-Object System.Drawing.Bitmap $TargetPx, $TargetPx, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        try {
            $sx0 = $src.Width / 2.0
            $sy0 = $src.Height / 2.0
            $scale = $src.Width / [double]$TargetPx

            for ($y = 0; $y -lt $TargetPx; $y++) {
                for ($x = 0; $x -lt $TargetPx; $x++) {
                    $dx = $x + 0.5 - $center
                    $dy = $y + 0.5 - $center
                    $d = [math]::Sqrt($dx * $dx + $dy * $dy)

                    $alpha = 255.0
                    if ($d -gt ($radius - $feather)) {
                        $t = ($radius + $feather - $d) / (2.0 * $feather)
                        $alpha = 255.0 * $t
                        if ($alpha -le 0.0) { continue }
                    }

                    $sx = [int][math]::Floor($sx0 + $dx * $scale)
                    $sy = [int][math]::Floor($sy0 + $dy * $scale)
                    if ($sx -lt 0) { $sx = 0 }
                    if ($sy -lt 0) { $sy = 0 }
                    if ($sx -ge $src.Width) { $sx = $src.Width - 1 }
                    if ($sy -ge $src.Height) { $sy = $src.Height - 1 }

                    $sp = $src.GetPixel($sx, $sy)
                    $a = [int][math]::Round($alpha)
                    if ($a -gt 255) { $a = 255 }
                    $dst.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($a, $sp.R, $sp.G, $sp.B))
                }
            }

            if (-not $WhatIf) {
                # Cannot save over the file the source bitmap still has open
                # (GDI+ "a generic error occurred"). Save to temp, then replace.
                $tmp = $f.FullName + ".tmp.png"
                $dst.Save($tmp, [System.Drawing.Imaging.ImageFormat]::Png)
                $src.Dispose()
                Move-Item -LiteralPath $tmp -Destination $f.FullName -Force
                $src = New-Object System.Drawing.Bitmap $f.FullName
            }
            $kb = [math]::Round((Get-Item $f.FullName).Length / 1KB, 1)
            $msg = "processed {0,-34} {1}px  {2} KB" -f $f.Name, $TargetPx, $kb
            Write-Output $msg
        }
        finally {
            $dst.Dispose()
        }
    }
    finally {
        $src.Dispose()
    }
}
