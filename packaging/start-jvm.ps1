# 启动 JVM 兜底包：使用随包携带的 jlink runtime。
param(
    [string]$Mode = 'integrated',
    [int]$HsmsPort = 5000,
    [switch]$NoBrowser,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = $PSScriptRoot
$java = Join-Path $root 'runtime\bin\java.exe'
$lib = Join-Path $root 'lib\*'
$web = Join-Path $root 'web'

if (-not (Test-Path $java)) { throw "JVM 不存在: $java" }
if (-not (Test-Path $web)) { throw "web 目录不存在: $web" }

if ($CheckOnly) {
    Write-Host "root=$root"
    Write-Host "java=$java"
    Write-Host "lib=$lib"
    Write-Host "web=$web"
    exit 0
}

$out = Join-Path $env:TEMP ("secs-sim-jvm-" + [guid]::NewGuid().ToString('N') + ".log")

$process = Start-Process -FilePath $java `
    -ArgumentList '-cp', $lib, 'com.shimizukenta.secssim.runtime.Main', `
        "--mode=$Mode", '--web-root', $web, '--demo-session', "--hsms-port=$HsmsPort" `
    -PassThru -RedirectStandardOutput $out -NoNewWindow

$ready = $null
for ($i = 0; $i -lt 300 -and -not $ready; $i++) {
    Start-Sleep -Milliseconds 50
    $match = Select-String -Path $out -Pattern '^READY ' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($match) { $ready = $match.Line }
}

if (-not $ready) {
    $process.Kill()
    throw "进程没有打印 READY，日志: $out"
}

$port = [regex]::Match($ready, 'http=(\d+)').Groups[1].Value
$token = [regex]::Match($ready, 'token=([0-9a-f]+)').Groups[1].Value
$url = "http://127.0.0.1:$port/#token=$token"

Write-Host $ready
Write-Host ''
Write-Host "界面地址: $url" -ForegroundColor Green
Write-Host "SECS 端口: $HsmsPort   日志: $out"
Write-Host '按 Ctrl+C 结束。'

if (-not $NoBrowser) { Start-Process $url }

try {
    $process.WaitForExit()
}
finally {
    if (-not $process.HasExited) { $process.Kill() }
}
