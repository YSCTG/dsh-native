# ============================================================
#  DSH Android -- manual APK build
#  No Gradle, no AndroidX: aapt2 -> javac -> d8 -> zipalign -> apksigner
#  Reuses JDK 17 + Android SDK from dsh-mobile/toolchain
#  Keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less
#  files as ANSI and mangles non-ASCII, which breaks parsing.
# ============================================================

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Proj = Join-Path $Root "android"
$Out  = Join-Path $Root "build\apk"

$TcCandidates = @(
    (Join-Path $Root "toolchain"),
    (Join-Path (Split-Path -Parent $Root) "dsh-mobile\toolchain")
)
$Tc = $null
foreach ($c in $TcCandidates) { if (Test-Path (Join-Path $c "android-sdk")) { $Tc = $c; break } }
if (-not $Tc) { throw "Android toolchain not found (need JDK + android-sdk)" }

$Sdk = Join-Path $Tc "android-sdk"
$Jdk = Get-ChildItem $Tc -Directory | Where-Object { $_.Name -like 'jdk-*' } | Select-Object -First 1
if (-not $Jdk) { throw "JDK not found" }
$env:JAVA_HOME = $Jdk.FullName
$env:ANDROID_HOME = $Sdk

$BuildTools = Join-Path $Sdk "build-tools\34.0.0"
if (-not (Test-Path $BuildTools)) {
    $alt = Get-ChildItem (Join-Path $Sdk "build-tools") -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $alt) { throw "build-tools not found" }
    $BuildTools = $alt.FullName
}
$Aapt2     = Join-Path $BuildTools "aapt2.exe"
$D8        = Join-Path $BuildTools "d8.bat"
$Zipalign  = Join-Path $BuildTools "zipalign.exe"
$ApkSigner = Join-Path $BuildTools "apksigner.bat"

$AndroidJar = Join-Path $Sdk "platforms\android-35\android.jar"
if (-not (Test-Path $AndroidJar)) {
    $p = Get-ChildItem (Join-Path $Sdk "platforms") -Directory | Select-Object -First 1
    if (-not $p) { throw "android.jar not found" }
    $AndroidJar = Join-Path $p.FullName "android.jar"
}

$MinSdk = 26
# targetSdk 28：targetSdk>=29 的 W^X 禁止从应用 dataDir execve，
# 内置的 git/curl/jq/node 就跑不起来。Termux 也是这么干的。
$TgtSdk = 28

$Gen = Join-Path $Out "gen"
$Cls = Join-Path $Out "classes"
$Dex = Join-Path $Out "dex"
Remove-Item $Out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Out, $Gen, $Cls, $Dex | Out-Null

$Javac   = Join-Path $Jdk.FullName "bin\javac.exe"
# 第三方 jar：Shizuku API（执行 shell 的桥）
$LibsAll = @() | ForEach-Object { Join-Path $Root $_ } | Where-Object { Test-Path $_ }
$LibsCp   = ($LibsAll -join ";")
$LibsJars = @($LibsAll)
$Keytool = Join-Path $Jdk.FullName "bin\keytool.exe"

# ---- 1. signing key ----
$Keystore = Join-Path $Root "dshs.keystore"
if (-not (Test-Path $Keystore)) {
    Write-Host "[1/7] generate signing key"
    & $Keytool -genkeypair -keystore $Keystore -alias dshandroid -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass dshandroid -keypass dshandroid `
        -dname "CN=DSH Android, OU=Mobile, O=DSH, L=Local, ST=Local, C=CN" 2>&1 | Out-Null
} else {
    Write-Host "[1/7] reuse signing key"
}

# ---- 2. resources ----
Write-Host "[2/7] aapt2 compile"
$compiled = Join-Path $Out "compiled.zip"
& $Aapt2 compile --dir (Join-Path $Proj "res") -o $compiled
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[3/7] aapt2 link"
$unaligned = Join-Path $Out "app-unaligned.apk"
$assetsDir = Join-Path $Proj "assets"
$assetsArg = @()
# 注意：aapt2 在 Windows 上会把 -A 目录里的路径用反斜杠写进 zip，
# 结果 AssetManager 按 "scaffold/index.html" 永远找不到（列表里是 scaffold\index.html）。
# 传正斜杠路径可以让它写出正确分隔符。
if (Test-Path $assetsDir) {
    $assetsArg = @("-A", ($assetsDir -replace '\\', '/'))
}
& $Aapt2 link -o $unaligned -I $AndroidJar `
    --manifest (Join-Path $Proj "AndroidManifest.xml") `
    --java $Gen `
    --min-sdk-version $MinSdk --target-sdk-version $TgtSdk `
    @assetsArg `
    $compiled
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# ---- 3. java ----
Write-Host "[4/7] javac"
$sources = @()
$sources += (Get-ChildItem $Gen -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem (Join-Path $Proj "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcList = Join-Path $Out "sources.txt"
$sources | Set-Content -Path $srcList -Encoding UTF8
& $Javac -source 8 -target 8 -nowarn -bootclasspath $AndroidJar -classpath "$AndroidJar;$LibsCp" `
    -d $Cls -encoding UTF-8 "@$srcList" 2>&1 | ForEach-Object { Write-Host "   $_" }
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# ---- 4. dex ----
Write-Host "[5/7] d8"
# Too many .class files blow Windows' command-line limit, so pack them into one jar first.
$clsJar = Join-Path $Out "classes.jar"
$Jar = Join-Path $Jdk.FullName "bin\jar.exe"
& $Jar cf $clsJar -C $Cls .
& $D8 --lib $AndroidJar --min-api $MinSdk --output $Dex $clsJar @LibsJars 2>&1 |
    Where-Object { $_ -notmatch '^   Warning' } | ForEach-Object { Write-Host "   $_" }
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# ---- 5. package ----
Write-Host "[6/7] package"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unaligned, [System.IO.Compression.ZipArchiveMode]::Update)
foreach ($dex in (Get-ChildItem $Dex -Filter "classes*.dex")) {
    $e = $zip.GetEntry($dex.Name); if ($e) { $e.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dex.FullName, $dex.Name) | Out-Null
}
# LibreOffice 引擎自带的 dex（org.libreoffice.* 那层胶水）：作为 classes2/3.dex 打进包，
# 我们的代码用反射调用，不参与编译。
$loDex = Join-Path $Proj "lodex"
if (Test-Path $loDex) {
    foreach ($d in (Get-ChildItem $loDex -Filter "classes*.dex")) {
        $e = $zip.GetEntry($d.Name); if ($e) { $e.Delete() }
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $d.FullName, $d.Name) | Out-Null
        Write-Host "   + $($d.Name) ($([math]::Round($d.Length/1MB,1)) MB)"
    }
}

# 原生库：Python 解释器与依赖。放在 lib/ 下，安装时会被解到 nativeLibraryDir，
# 那里是唯一允许 execve 的应用私有目录（dataDir 在 targetSdk>=29 上被 W^X 拦住）。
$nativeDir = Join-Path $Proj "native\arm64-v8a"
if (Test-Path $nativeDir) {
    foreach ($nf in (Get-ChildItem $nativeDir -File)) {
        $entryName = "lib/arm64-v8a/" + $nf.Name
        $e = $zip.GetEntry($entryName); if ($e) { $e.Delete() }
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $nf.FullName, $entryName,
            [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        Write-Host "   + $entryName"
    }
}
$zip.Dispose()

$aligned = Join-Path $Out "app-aligned.apk"
& $Zipalign -f 4 $unaligned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

# ---- 6. sign ----
Write-Host "[7/7] apksigner"
$final = Join-Path $Root "DSHNative.apk"
Remove-Item $final -ErrorAction SilentlyContinue
& $ApkSigner sign --ks $Keystore --ks-pass pass:dshandroid --key-pass pass:dshandroid `
    --ks-key-alias dshandroid --min-sdk-version $MinSdk `
    --out $final $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& $ApkSigner verify $final 2>&1 | ForEach-Object { Write-Host "   $_" }

$f = Get-Item $final
Write-Host ""
Write-Host "  [OK] $final" -ForegroundColor Green
Write-Host "       $([math]::Round($f.Length/1024,1)) KB" -ForegroundColor Green
