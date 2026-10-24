# 启动打包好的模拟器：拉起进程 -> 读 READY 行 -> 打开浏览器。
param(
    [string]$Mode = 'integrated',
    [int]$HsmsPort = 5000,
    [switch]$NoBrowser,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

# start.ps1 被拷到绿色包根目录，$PSScriptRoot 就是包根；
# 不要再 -Parent，否则会去上一级找 exe（M9 踩过：双击 start.cmd 直接报 exe 不存在）。
$root = $PSScriptRoot
$exe = Join-Path $root 'secs-sim.exe'
$web = Join-Path $root 'web'

if (-not (Test-Path $exe)) { throw "secs-sim.exe 不存在: $exe" }
if (-not (Test-Path $web)) { throw "web 目录不存在: $web" }

# 打包与 CI 用：只校验路径，不拉起进程。
if ($CheckOnly) {
    Write-Host "root=$root"
    Write-Host "exe=$exe"
    Write-Host "web=$web"
    exit 0
}

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
# 默认开放访问（内部工具）；显式配置了凭据时 READY 行带 token，放进 hash（不进服务端日志）
$tokenMatch = [regex]::Match($ready, 'token=([0-9a-f]+)')
$url = "http://127.0.0.1:$port/"
if ($tokenMatch.Success) { $url = "$url#token=$($tokenMatch.Groups[1].Value)" }

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
