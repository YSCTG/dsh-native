# Swift 语法校验（Windows 上唯一能做的验证）
#
# 背景：这台机器装不了 MSVC（VS Installer 三次尝试分别返回 5007/87），
# 而 Swift on Windows 缺 link.exe 就无法构建；GitHub 的 macOS runner 对本仓库
# 长期排队（6 次运行排队数小时未分配）。所以本地只能做到 `swiftc -parse`：
# 语法错误能抓，类型错误抓不到。
#
# 用法：pwsh ios/check-syntax.ps1
$ErrorActionPreference = "Stop"

$swiftBin = Get-ChildItem "$env:LOCALAPPDATA\Programs\Swift\Toolchains\*\usr\bin\swiftc.exe" -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $swiftBin) { throw "找不到 swiftc.exe：先装 Swift 工具链（winget install Swift.Toolchain）" }

$root = Join-Path $PSScriptRoot "DSHEngine"
$files = @(Get-ChildItem "$root\Sources\DSHEngine\*.swift") + @(Get-ChildItem "$root\Tests\DSHEngineTests\*.swift")
$bad = 0
foreach ($f in $files) {
    $out = & $swiftBin.FullName -parse $f.FullName 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host ("  OK    {0}" -f $f.Name)
    } else {
        $bad++
        Write-Host ("  FAIL  {0}" -f $f.Name) -ForegroundColor Red
        ($out | Select-Object -First 4) | ForEach-Object { Write-Host ("        {0}" -f $_.Trim()) }
    }
}
Write-Host ""
if ($bad -gt 0) { Write-Host ("{0} 个文件语法有错" -f $bad) -ForegroundColor Red; exit 1 }
Write-Host ("{0} 个文件语法通过（注意：只校验语法，类型与运行行为未验证）" -f $files.Count)