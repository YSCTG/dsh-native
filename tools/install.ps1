# ============================================================
#  Install the APK on any Android device.
#
#  Why this exists: OEMs wrap ADB installs in 1-3 confirmation
#  dialogs (Huawei shows 风险提示 + 纯净模式 risk check, MIUI shows
#  its own), and `adb install` blocks until somebody taps them.
#  This script walks the dialogs by text, and prefers `pm install`
#  which normally skips the installer UI entirely.
#
#  Usage:  powershell -File tools\install.ps1 [-Serial <id>]
#  ASCII-only on purpose (Windows PowerShell 5.1 mangles UTF-8 here).
# ============================================================

param([string]$Serial = "")

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Apk  = Join-Path $Root "DSHS.apk"
. (Join-Path $PSScriptRoot "env.ps1")

$target = @()
if ($Serial -ne "") { $target = @("-s", $Serial) }

function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$a)
    & $ADB @target @a 2>&1
}

# Button labels that mean "yes, install it" across roms and languages.
$CONTINUE = @(
    "继续安装", "仍要安装", "继续", "安装", "确定", "允许",
    "Install anyway", "Install", "Continue", "OK"
)

function UiText {
    (Adb shell uiautomator dump /sdcard/_i.xml) | Out-Null
    ((Adb shell cat /sdcard/_i.xml) -join "")
}

function TapText([string]$label) {
    $xml = UiText
    $doc = $null
    try { $doc = [xml]$xml } catch { return $false }
    $node = $doc.SelectSingleNode("//node[@text='$label']")
    if ($null -eq $node) { return $false }
    if ($node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { return $false }
    $cx = ([int]$Matches[1] + [int]$Matches[3]) / 2
    $cy = ([int]$Matches[2] + [int]$Matches[4]) / 2
    (Adb shell input tap $cx $cy) | Out-Null
    return $true
}

function ClearDialogs([int]$maxTaps = 6) {
    $taps = 0
    for ($i = 0; $i -lt 12; $i++) {
        $xml = UiText
        $hit = $false
        foreach ($label in $CONTINUE) {
            if ($xml.Contains("text=`"$label`"")) {
                if (TapText $label) {
                    Write-Host "  tapped '$label'"
                    $taps++
                    $hit = $true
                    Start-Sleep -Seconds 2
                    break
                }
            }
        }
        if (-not $hit) { break }
        if ($taps -ge $maxTaps) { break }
    }
    return $taps
}

Write-Host "[1/3] devices"
$devs = (Adb devices) -join "`n"
Write-Host $devs
if ($devs -notmatch '\sdevice') { throw "no device" }

Write-Host "[2/3] install via pm (skips installer UI)"
(Adb push $Apk /data/local/tmp/dshs.apk) | Out-Null
$out = (Adb shell pm install -r /data/local/tmp/dshs.apk) -join "`n"
Write-Host $out

if ($out -notmatch 'Success') {
    Write-Host "[2b] pm failed, falling back to adb install + dialog walking"
    $job = Start-Process -FilePath $ADB -ArgumentList (@() + $target + @("install", "-r", $Apk)) -PassThru -WindowStyle Hidden
    $taps = 0
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 2
        if ($job.HasExited) { break }
        $taps += ClearDialogs 2
    }
    Write-Host ("  dialog taps: {0}  exited: {1}" -f $taps, $job.HasExited)
}

Write-Host "[3/3] verify"
$pkg = (Adb shell dumpsys package com.dshs) -join "`n"
if ($pkg -match 'versionName=(\S+)') {
    Write-Host ("  installed version: " + $Matches[1])
} else {
    throw "package not installed"
}
Write-Host ""
Write-Host "  Done. If the app still looks old, the install never landed -"
Write-Host "  check for a dialog still waiting on the phone screen."
