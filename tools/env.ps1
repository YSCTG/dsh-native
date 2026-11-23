# Environment for driving the Android emulator inside this workspace.
# Dot-source it:  . .\tools\env.ps1
#
# Why this file exists: the harness file sandbox denies writes to %TEMP%, and
# the emulator + adb server both want to write there (adb.log, feature-flag
# locks). Pointing TMP/TEMP/ANDROID_EMULATOR_HOME into the workspace fixes it.

$PSSdk  = "C:\Users\1\Documents\deepseek-harness\default-workspace\dsh-mobile\toolchain\android-sdk"
$PSRoot = "C:\Users\1\Documents\deepseek-harness\default-workspace\dshs"

$env:ANDROID_SDK_ROOT       = $PSSdk
$env:ANDROID_HOME           = $PSSdk
$env:ANDROID_EMULATOR_HOME  = Join-Path $PSRoot "build\emuhome"
$env:ANDROID_AVD_HOME       = Join-Path $PSRoot "build\avd"
$env:TMP                    = Join-Path $PSRoot "build\tmp"
$env:TEMP                   = $env:TMP

foreach ($d in @($env:ANDROID_EMULATOR_HOME, $env:TMP)) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

$ADB = Join-Path $PSSdk "platform-tools\adb.exe"
$EMU = Join-Path $PSSdk "emulator\emulator.exe"
$JDK = (Get-ChildItem (Join-Path (Split-Path -Parent $PSRoot) "dsh-mobile\toolchain") -Directory |
        Where-Object { $_.Name -like 'jdk-*' } | Select-Object -First 1).FullName
