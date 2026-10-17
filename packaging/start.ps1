# 启动打包好的模拟器：拉起进程 -> 读 READY 行 -> 打开浏览器。
param(
    [string]$Mode = 'integrated',
    [int]$HsmsPort = 5000
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = Split-Path $PSScriptRoot -Parent
$exe = Join-Path $root 'secs-sim.exe'
$web = Join-Path $root 'web'

if (-not (Test-Path $exe)) { throw "secs-sim.exe 不存在: $exe" }
if (-not (Test-Path $web)) { throw "web 目录不存在: $web" }

$out = Join-Path $env:TEMP ("secs-sim-" + [guid]::NewGuid().ToString('N') + ".log")

$process = Start-Process -FilePath $exe `
    -ArgumentList "--mode=$Mode", "--web-root", $web, "--demo-session", "--hsms-port", $HsmsPort `
    -PassThru -RedirectStandardOutput $out -NoNewWindow

$ready = $null
for ($i = 0; $i -lt 200 -and -not $ready; $i++) {
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
# token 放在 hash 里：hash 不会发给服务端，也不进访问日志
$url = "http://127.0.0.1:$port/#token=$token"

Write-Host $ready
Write-Host ''
Write-Host "界面地址: $url" -ForegroundColor Green
Write-Host "SECS 端口: $HsmsPort   日志: $out"
Write-Host '按 Ctrl+C 结束。'

Start-Process $url

try {
    $process.WaitForExit()
}
finally {
    if (-not $process.HasExited) { $process.Kill() }
}
