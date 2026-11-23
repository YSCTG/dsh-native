# ============================================================
#  DSHS end-to-end verification on the emulator.
#  Boots AVD (if needed) -> installs APK -> seeds key/base through
#  the real dialog -> runs turns against the local mock server ->
#  asserts transcript, audit chain, and the accessibility tier.
#
#  Prereq: mock server running (tools/mock_ds.py 8899)
#  Run:    cd dshs ; powershell -File tools\e2e.ps1
# ============================================================

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot "env.ps1")

$Root = Split-Path -Parent $PSScriptRoot
$Apk  = Join-Path $Root "DSHS.apk"
$Out  = Join-Path $Root "build\e2e"
$Pkg  = "com.dshs"
New-Item -ItemType Directory -Force -Path $Out | Out-Null

$script:fail = 0
function Check($cond, $msg, $detail) {
    if ($cond) { Write-Host "  ok   $msg" -ForegroundColor Green }
    else { Write-Host "  FAIL $msg   $detail" -ForegroundColor Red; $script:fail++ }
}
function Note($msg) { Write-Host $msg -ForegroundColor Cyan }

function Devices { (& $ADB devices 2>&1) -join "`n" }
function BootedNow {
    $b = (& $ADB shell getprop sys.boot_completed 2>&1) -join ""
    return ($b.Trim() -eq "1")
}

# ---------- 1. emulator ----------
Note "[1/7] emulator"
if ((Devices) -notmatch 'emulator-\d+\s+device') {
    Note "  no device, starting emulator"
    Start-Process -FilePath $EMU -ArgumentList @(
        "-avd","dshs","-no-snapshot","-no-boot-anim","-no-audio","-no-window",
        "-gpu","swiftshader_indirect","-netdelay","none","-netspeed","full","-port","5554"
    ) -RedirectStandardOutput "NUL" -RedirectStandardError "$env:ANDROID_EMULATOR_HOME\emu.err.log" -WindowStyle Hidden | Out-Null
}
$deadline = (Get-Date).AddSeconds(300)
while ((Get-Date) -lt $deadline) {
    if ((Devices) -match 'emulator-\d+\s+device' -and (BootedNow)) { break }
    Start-Sleep -Seconds 5
}
Check ((Devices) -match 'emulator-\d+\s+device') "device connected" (Devices)
Check (BootedNow) "android boot completed" ""
if (-not (BootedNow)) {
    Write-Host "--- emulator err ---"; Get-Content "$env:ANDROID_EMULATOR_HOME\emu.err.log" -Tail 25 -ErrorAction SilentlyContinue
    exit 1
}
(& $ADB root 2>&1) | Out-Null
Start-Sleep -Seconds 3

# ---------- 2. install ----------
Note "[2/7] install"
(& $ADB uninstall $Pkg 2>&1) | Out-Null
$inst = (& $ADB install -r $Apk 2>&1) -join "`n"
Check ($inst -match 'Success') "apk installed" $inst

# mock reachability from inside the guest (informational: the image may have no curl/wget)
$ping = (& $ADB shell "toybox wget -q -O - http://10.0.2.2:8899/health" 2>&1) -join ""
if ($ping -notmatch 'ok') { $ping = (& $ADB shell "curl -s -m 5 http://10.0.2.2:8899/health" 2>&1) -join "" }
if ($ping -match 'ok') { Write-Host "  ok   guest reaches mock server" -ForegroundColor Green }
else { Write-Host "  skip guest reachability probe (no curl/wget in image; turn 1 proves it)" -ForegroundColor DarkYellow }

# ---------- 3. seed credentials through the UI ----------
Note "[3/7] seed key + base URL via the real dialog"
(& $ADB shell am force-stop $Pkg 2>&1) | Out-Null
(& $ADB shell am start -n "$Pkg/.MainActivity" 2>&1) | Out-Null
Start-Sleep -Seconds 4

function DumpUi {
    (& $ADB shell uiautomator dump /sdcard/ui.xml 2>&1) | Out-Null
    (& $ADB shell cat /sdcard/ui.xml 2>&1) -join ""
}
function UiText {
    $xml = DumpUi
    try { $doc = [xml]$xml } catch { return $xml }
    return (($doc.SelectNodes("//node[@text!='']") | ForEach-Object { $_.text }) -join "`n")
}
function TapText($needle) {
    $xml = DumpUi
    $doc = $null
    try { $doc = [xml]$xml } catch { return $false }
    $hit = $doc.SelectSingleNode("//node[contains(@text,'$needle')]")
    if ($null -eq $hit) { return $false }
    if ($hit.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { return $false }
    $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
    $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
    (& $ADB shell input tap $x $y 2>&1) | Out-Null
    Start-Sleep -Milliseconds 900
    return $true
}

(& $ADB shell input text "sk-e2e-test" 2>&1) | Out-Null
(& $ADB shell input keyevent 61 2>&1) | Out-Null
Start-Sleep -Milliseconds 500
(& $ADB shell input keyevent 123 2>&1) | Out-Null
(& $ADB shell input text "http://10.0.2.2:8899" 2>&1) | Out-Null
Start-Sleep -Milliseconds 500
Check (TapText "保存") "tapped save" ""
Start-Sleep -Seconds 2

$prefs = (& $ADB shell "cat /data/data/$Pkg/shared_prefs/dshs.xml" 2>&1) -join "`n"
Check ($prefs -match 'sk-e2e-test') "api_key persisted" $prefs
Check ($prefs -match 'http://10\.0\.2\.2:8899') "base url persisted exactly" $prefs
Check ($prefs -notmatch 'comhttp') "base url not concatenated onto default" $prefs

# ---------- 4. fs tier turn ----------
Note "[4/7] turn 1: fs_list (app tier)"
(& $ADB shell am force-stop $Pkg 2>&1) | Out-Null
(& $ADB shell am start -n "$Pkg/.MainActivity" --es prompt "看看当前目录" 2>&1) | Out-Null
Start-Sleep -Seconds 12
$t1 = UiText
if ($t1 -notmatch 'mock') { Write-Host "  -- ui text --`n$t1" -ForegroundColor DarkYellow }
Check ($t1 -match 'mock') "turn1 transcript has mock reply" ""
Check ($t1 -match 'fs_list') "turn1 transcript has fs_list tool card" ""
Check ($t1 -match '命中') "turn1 ledger shows cache hit rate" ""

# ---------- 5. audit ----------
Note "[5/7] audit chain"
$audit = (& $ADB shell "cat /data/data/$Pkg/files/audit.log" 2>&1) -join "`n"
Check ($audit -match 'fs\.read') "audit recorded fs.read" $audit
$first = ($audit -split "`n")[0]
Check ($first -match '^[0-9a-f]{64}\s') "audit line is hash-chained" "got: $first"

# ---------- 6. accessibility tier ----------
Note "[6/7] turn 2: ui_dump (a11y tier)"
# 必须在 App 活着的时候开服务：先 force-stop 再改设置，系统会把它记成 crashed 并禁用
(& $ADB shell settings put secure enabled_accessibility_services "$Pkg/$Pkg.UiAgentService" 2>&1) | Out-Null
(& $ADB shell settings put secure accessibility_enabled 1 2>&1) | Out-Null
Start-Sleep -Seconds 4
$bound = (& $ADB shell dumpsys accessibility 2>&1) -join "`n"
Check ($bound -match 'Bound services:\{Service\[label=DSHS') "a11y service actually bound" ""
Check ($bound -notmatch 'Crashed services:\{\{') "a11y service not marked crashed" ""

# 工具清单在会话内冻结，要开新会话才拿得到 ui_*；不 force-stop，免得把绑定弄断
Check (TapText "新会话") "tapped new-session (unfreeze tool list)" ""
Start-Sleep -Seconds 1
(& $ADB shell am start -n "$Pkg/.MainActivity" --es prompt "屏幕上有什么，读一下界面" 2>&1) | Out-Null
Start-Sleep -Seconds 14
$t2 = UiText
if ($t2 -notmatch 'ui_dump') { Write-Host "  -- ui text --`n$t2" -ForegroundColor DarkYellow }
Check ($t2 -match 'app\+a11y') "status line shows a11y tier active" ""
Check ($t2 -match 'ui_dump [1-9]\d* 节点') "ui_dump returned a non-empty node list" ""

$audit2 = (& $ADB shell "cat /data/data/$Pkg/files/audit.log" 2>&1) -join "`n"
Check ($audit2 -match 'ui\.read') "audit recorded ui.read" ""

# ---------- 7. screenshot ----------
Note "[7/7] screenshot"
(& $ADB shell screencap -p /sdcard/shot.png 2>&1) | Out-Null
(& $ADB pull /sdcard/shot.png (Join-Path $Out "shot.png") 2>&1) | Out-Null
Check (Test-Path (Join-Path $Out "shot.png")) "screenshot captured" ""

Write-Host ""
if ($script:fail -eq 0) {
    Write-Host "E2E PASS" -ForegroundColor Green
} else {
    Write-Host ("E2E FAIL ({0})" -f $script:fail) -ForegroundColor Red
    Write-Host "--- logcat (dshs / crashes) ---"
    (& $ADB logcat -d -t 300 2>&1) | Select-String -Pattern "dshs|AndroidRuntime|FATAL" | Select-Object -Last 40
    exit 1
}
