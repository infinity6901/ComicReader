Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

$root = "C:\SOUGATA\REA\ComicReader\testcontent"
if (Test-Path $root) { Remove-Item $root -Recurse -Force }
New-Item -ItemType Directory -Force -Path $root | Out-Null
$pagesDir = Join-Path $root "pages"
New-Item -ItemType Directory -Force -Path $pagesDir | Out-Null

function New-Page([string]$path, [int]$number, [System.Drawing.Color]$bg) {
    $bmp = New-Object System.Drawing.Bitmap 800, 1200
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear($bg)
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), 8
    $g.DrawRectangle($pen, 20, 20, 760, 1160)
    $font = New-Object System.Drawing.Font "Arial", 200, ([System.Drawing.FontStyle]::Bold)
    $brush = [System.Drawing.Brushes]::White
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = 'Center'; $sf.LineAlignment = 'Center'
    $g.DrawString("$number", $font, $brush, (New-Object System.Drawing.RectangleF 0,0,800,1200), $sf)
    $font2 = New-Object System.Drawing.Font "Arial", 40
    $g.DrawString("ComicReader test page $number", $font2, $brush, 60, 1050)
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

# Loose images
$colors = @('#1e3a8a','#166534','#7c2d12','#581c87','#0f766e')
for ($i = 1; $i -le 5; $i++) {
    $c = [System.Drawing.ColorTranslator]::FromHtml($colors[$i-1])
    New-Page (Join-Path $pagesDir ("{0:D3}.png" -f $i)) $i $c
}

# CBZ: zip of the page PNGs
$cbz = Join-Path $root "sample.cbz"
if (Test-Path $cbz) { Remove-Item $cbz -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($pagesDir, $cbz)

# A second CBZ with wide pages, to exercise the two-page spread
$wideDir = Join-Path $root "wide"
New-Item -ItemType Directory -Force -Path $wideDir | Out-Null
for ($i = 1; $i -le 4; $i++) {
    $bmp = New-Object System.Drawing.Bitmap 1200, 1200
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.ColorTranslator]::FromHtml($colors[$i-1]))
    $font = New-Object System.Drawing.Font "Arial", 160, ([System.Drawing.FontStyle]::Bold)
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = 'Center'; $sf.LineAlignment = 'Center'
    $g.DrawString("$i", $font, [System.Drawing.Brushes]::White, (New-Object System.Drawing.RectangleF 0,0,1200,1200), $sf)
    $g.Dispose()
    $bmp.Save((Join-Path $wideDir ("{0:D3}.png" -f $i)), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}
$cbz2 = Join-Path $root "spread.cbz"
if (Test-Path $cbz2) { Remove-Item $cbz2 -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory($wideDir, $cbz2)

# Minimal multi-page PDF with a correct xref table
function New-Pdf([string]$path, [int]$pageCount) {
    $objs = New-Object System.Collections.ArrayList
    # 1: catalog, 2: pages, then per page: page, content, then font last
    $fontObjNum = 3 + ($pageCount * 2)
    [void]$objs.Add("<</Type/Catalog/Pages 2 0 R>>")
    $kids = (1..$pageCount | ForEach-Object { "$(3 + (($_ - 1) * 2)) 0 R" }) -join " "
    [void]$objs.Add("<</Type/Pages/Kids[$kids]/Count $pageCount>>")
    for ($p = 1; $p -le $pageCount; $p++) {
        $pageNum = 3 + (($p - 1) * 2)
        $contentNum = $pageNum + 1
        [void]$objs.Add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 600 800]/Contents $contentNum 0 R/Resources<</Font<</F1 $fontObjNum 0 R>>>>>>")
        $stream = "BT /F1 180 Tf 180 350 Td (P$p) Tj ET"
        [void]$objs.Add("<</Length $($stream.Length)>>`nstream`n$stream`nendstream")
    }
    [void]$objs.Add("<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>")

    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append("%PDF-1.4`n")
    $offsets = @()
    for ($i = 0; $i -lt $objs.Count; $i++) {
        $offsets += $sb.Length
        [void]$sb.Append("$($i + 1) 0 obj`n$($objs[$i])`nendobj`n")
    }
    $xrefPos = $sb.Length
    [void]$sb.Append("xref`n0 $($objs.Count + 1)`n")
    [void]$sb.Append("0000000000 65535 f `n")
    foreach ($o in $offsets) { [void]$sb.Append(("{0:D10} 00000 n `n" -f $o)) }
    [void]$sb.Append("trailer`n<</Size $($objs.Count + 1)/Root 1 0 R>>`nstartxref`n$xrefPos`n%%EOF`n")

    [System.IO.File]::WriteAllText($path, $sb.ToString(), [System.Text.Encoding]::ASCII)
}
New-Pdf (Join-Path $root "sample.pdf") 4

"=== generated ==="
Get-ChildItem $root -Recurse -File | ForEach-Object { "{0}  ({1:N1} KB)" -f $_.FullName.Replace($root, ''), ($_.Length / 1KB) }
