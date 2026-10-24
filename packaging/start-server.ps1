# 启动运行时后端：server 模式对外提供服务，供远程界面连接。
#
# 与完整安装包的 start.ps1 的区别：不带 web 目录（界面在安装包那台机器上），
# 默认绑 0.0.0.0 对外监听；远程机器上的界面用「连接设置」指向本机地址即可。
param(
    [int]$Port = 8080,
    [string]$Bind = '0.0.0.0',
    [int]$HsmsPort = 5000,
    [string]$WorkDir = (Join-Path $PSScriptRoot 'work'),
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = $PSScriptRoot
$exe = Join-Path $root 'secs-sim.exe'

if (-not (Test-Path $exe)) { throw "secs-sim.exe 不存在: $exe" }
if (-not (Test-Path $WorkDir)) { New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null }

# 打包与 CI 用：只校验路径，不拉起进程。
if ($CheckOnly) {
    Write-Host "root=$root"
    Write-Host "exe=$exe"
    Write-Host "work=$WorkDir"
    exit 0
}

$out = Join-Path $env:TEMP ("secs-sim-runtime-" + [guid]::NewGuid().ToString('N') + ".log")

$process = Start-Process -FilePath $exe `
    -ArgumentList "--mode=server", "--bind", $Bind, "--port", $Port, "--hsms-port", $HsmsPort, "--work-dir", $WorkDir `
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

$apiPort = [regex]::Match($ready, 'http=(\d+)').Groups[1].Value

Write-Host $ready
Write-Host ''
Write-Host "API 地址: http://$([System.Net.Dns]::GetHostName()):$apiPort  （远程界面在「连接设置」里填它）" -ForegroundColor Green
Write-Host "HSMS 端口: $HsmsPort   数据目录: $WorkDir   日志: $out"
Write-Host '按 Ctrl+C 结束。'

try {
    Wait-Process -Id $process.Id -ErrorAction SilentlyContinue
} catch {
    # 进程退出即结束脚本
}
