$ErrorActionPreference = 'Stop'
$sw = [Diagnostics.Stopwatch]::StartNew()

$sdk   = 'D:\AndroidSdk'
$bt    = (Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name | Select-Object -Last 1).FullName
$plat  = (Get-ChildItem (Join-Path $sdk 'platforms') -Directory | Select-Object -First 1).FullName
$ajar  = Join-Path $plat 'android.jar'
$jdk   = 'C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot'
$env:JAVA_HOME = $jdk
$proj  = 'D:\English_Road\elderly-fall-detection\android-app'
$build = Join-Path $proj 'build'
$dist  = Join-Path $proj 'dist'

Write-Host "SDK: $plat"
Write-Host "BT : $bt"

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
foreach ($d in @('gen','classes','dex','res')) { New-Item -ItemType Directory -Force -Path (Join-Path $build $d) | Out-Null }
New-Item -ItemType Directory -Force -Path $dist | Out-Null

# ---------------- 1. icons ----------------
Add-Type -AssemblyName System.Drawing
New-Item -ItemType Directory -Force -Path (Join-Path $proj 'res\drawable') | Out-Null
function New-Icon([string]$path, [int]$size, [bool]$background) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([System.Drawing.Color]::Transparent)
    if ($background) {
        $brushBg = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(198, 40, 40))
        $r = [int]($size * 0.18)
        $p = New-Object System.Drawing.Drawing2D.GraphicsPath
        $w = $size - 1; $h = $size - 1; $d = $r * 2
        $p.AddArc(0, 0, $d, $d, 180, 90)
        $p.AddArc($w - $d, 0, $d, $d, 270, 90)
        $p.AddArc($w - $d, $h - $d, $d, $d, 0, 90)
        $p.AddArc(0, $h - $d, $d, $d, 90, 90)
        $p.CloseFigure()
        $g.FillPath($brushBg, $p)
        $fore = [System.Drawing.Color]::White
    } else {
        $fore = [System.Drawing.Color]::White
    }
    # warning triangle
    $tri = @(
        (New-Object System.Drawing.PointF([single]($size * 0.5), [single]($size * 0.20))),
        (New-Object System.Drawing.PointF([single]($size * 0.86), [single]($size * 0.82))),
        (New-Object System.Drawing.PointF([single]($size * 0.14), [single]($size * 0.82)))
    )
    $pen = New-Object System.Drawing.Pen($fore, [single]($size * 0.07))
    $pen.LineJoin = 'Round'
    $g.DrawPolygon($pen, $tri)
    $barW = [single]($size * 0.075)
    $bar = New-Object System.Drawing.SolidBrush($fore)
    $g.FillRectangle($bar, [single]($size * 0.4625), [single]($size * 0.42), $barW, [single]($size * 0.22))
    $g.FillRectangle($bar, [single]($size * 0.4625), [single]($size * 0.69), $barW, [single]($size * 0.07))
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host ("  icon " + (Split-Path $path -Leaf) + " " + $size + "px")
}
New-Icon (Join-Path $proj 'res\drawable\ic_launcher.png') 192 $true
New-Icon (Join-Path $proj 'res\drawable\ic_stat.png') 96 $false

# ---------------- 2. aapt2 compile + link ----------------
Write-Host '[2/6] aapt2 compile'
& (Join-Path $bt 'aapt2.exe') compile --dir (Join-Path $proj 'res') -o (Join-Path $build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '[3/6] aapt2 link'
& (Join-Path $bt 'aapt2.exe') link -o (Join-Path $build 'base.apk') -I $ajar `
    --manifest (Join-Path $proj 'AndroidManifest.xml') `
    --java (Join-Path $build 'gen') -R (Join-Path $build 'res.zip') `
    --auto-add-overlay --min-sdk-version 21 --target-sdk-version 33
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

# ---------------- 3. javac ----------------
Write-Host '[4/6] javac'
$srcs = @()
Get-ChildItem (Join-Path $proj 'src') -Recurse -Filter *.java | ForEach-Object { $srcs += $_.FullName }
Get-ChildItem (Join-Path $build 'gen') -Recurse -Filter *.java | ForEach-Object { $srcs += $_.FullName }
Write-Host ("  java files: " + $srcs.Count)

$argFile = Join-Path $build 'javac_args.txt'
$args = @(
    '-encoding', 'UTF-8',
    '-source', '8', '-target', '8',
    '-nowarn', '-Xlint:none',
    '-bootclasspath', ($ajar -replace '\\', '/'),
    '-d', (($build + '\classes') -replace '\\', '/')
)
foreach ($s in $srcs) { $args += ($s -replace '\\', '/') }
$args | Set-Content -LiteralPath $argFile -Encoding ASCII

& (Join-Path $jdk 'bin\javac.exe') ('@' + $argFile)
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

# ---------------- 4. d8 ----------------
Write-Host '[5/6] d8 (dex)'
& (Join-Path $jdk 'bin\jar.exe') cf (Join-Path $build 'classes.jar') -C (Join-Path $build 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
& (Join-Path $bt 'd8.bat') --min-api 21 --lib $ajar --output (Join-Path $build 'dex') (Join-Path $build 'classes.jar')
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

# ---------------- 5. pack dex into apk ----------------
Write-Host '[6/6] pack + align + sign'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$unsigned = Join-Path $build 'unsigned.apk'
Copy-Item (Join-Path $build 'base.apk') $unsigned -Force
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
$dex = Join-Path $build 'dex\classes.dex'
if (-not (Test-Path $dex)) { throw 'classes.dex missing' }
$entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
$es = $entry.Open()
$fs = [System.IO.File]::OpenRead($dex)
$fs.CopyTo($es)
$fs.Close(); $es.Close(); $zip.Dispose()
Write-Host ("  classes.dex packed: " + [math]::Round((Get-Item $dex).Length/1KB,1) + " KB")

$aligned = Join-Path $build 'aligned.apk'
& (Join-Path $bt 'zipalign.exe') -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$ks = Join-Path $proj 'keystore.jks'
if (-not (Test-Path $ks)) {
    Write-Host '  generating keystore'
    & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -v -keystore $ks -storetype JKS `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10950 `
        -storepass android -keypass android `
        -dname 'CN=Leeann323, OU=HomeCare, O=Leeann323, L=Beijing, ST=Beijing, C=CN' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

$apk = Join-Path $dist 'FallAlert-1.0.apk'
if (Test-Path $apk) { Remove-Item $apk -Force }
& (Join-Path $bt 'apksigner.bat') sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& (Join-Path $bt 'apksigner.bat') verify --print-certs $apk | Select-Object -First 4
& (Join-Path $bt 'aapt2.exe') dump badging $apk | Select-Object -First 6

Write-Host ''
Write-Host ("APK: " + $apk + "  " + [math]::Round((Get-Item $apk).Length/1MB,2) + " MB  in " + [math]::Round($sw.Elapsed.TotalSeconds,1) + "s")
