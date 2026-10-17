# M1 验收：领域模型 + 运行入口 + HTTP API + 原生端到端
param([switch]$SkipNative)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$graal = 'E:\Java\graalvm-jdk-25.0.4+7.1'
if (Test-Path $graal) { $env:JAVA_HOME = $graal }

Set-Location (Join-Path $PSScriptRoot '..')

Write-Host '=== [1/4] 构建与单元测试 ===' -ForegroundColor Cyan
& mvn.cmd -B -q verify
if ($LASTEXITCODE -ne 0) { Write-Host 'M1 FAIL: verify' -ForegroundColor Red; exit 1 }

if ($SkipNative) {
    Write-Host '=== [2-4/4] 原生相关步骤已跳过 ===' -ForegroundColor Yellow
    Write-Host ''
    Write-Host 'M1 PASS (JVM only)' -ForegroundColor Green
    exit 0
}

Write-Host '=== [2/4] 原生镜像 ===' -ForegroundColor Cyan
$sw = [Diagnostics.Stopwatch]::StartNew()
& mvn.cmd -B -q -Pnative -pl secs-cli -am '-DskipTests' package
if ($LASTEXITCODE -ne 0) { Write-Host 'M1 FAIL: native build' -ForegroundColor Red; exit 1 }
$sw.Stop()
$exe = Get-Item 'secs-cli/target/secs-sim.exe'
Write-Host ("  image      : {0} ({1:N1} MB, {2:N1} s)" -f $exe.Name, ($exe.Length / 1MB), $sw.Elapsed.TotalSeconds)

Write-Host '=== [3/4] 原生端到端（HTTP / 鉴权 / SSE / HSMS / 停机）===' -ForegroundColor Cyan
& mvn.cmd -B -q -pl secs-cli -am '-Dtest=NativeRuntimeIT' '-Dsurefire.failIfNoSpecifiedTests=false' test
if ($LASTEXITCODE -ne 0) { Write-Host 'M1 FAIL: native e2e' -ForegroundColor Red; exit 1 }

Write-Host '=== [4/4] 一体化模式冒烟 ===' -ForegroundColor Cyan
$out = [IO.Path]::GetTempFileName()
$proc = $null
try {
    # 不指定端口：由进程自己分配，再从 READY 行读回真实端口
    $proc = Start-Process -FilePath $exe.FullName -ArgumentList '--mode=integrated', "--work-dir=$env:TEMP\secs-sim-smoke" -PassThru -RedirectStandardOutput $out -NoNewWindow

    $ready = $null
    for ($i = 0; $i -lt 200 -and -not $ready; $i++) {
        Start-Sleep -Milliseconds 50
        $text = Get-Content $out -Raw -ErrorAction SilentlyContinue
        if ($text -match 'READY ') { $ready = ($text -split "\r?\n")[0] }
    }
    if (-not $ready) {
        Write-Host 'M1 FAIL: 没有 READY 行' -ForegroundColor Red
        exit 1
    }
    Write-Host ("  ready      : {0}" -f $ready)

    if ($ready -notmatch 'mode=integrated') { Write-Host 'M1 FAIL: mode 不是 integrated' -ForegroundColor Red; exit 1 }
    if ($ready -notmatch 'utf8=true') { Write-Host 'M1 FAIL: 中文路径往返失败' -ForegroundColor Red; exit 1 }
    if ($ready -notmatch 'http=(\d+)') { Write-Host 'M1 FAIL: READY 行缺少 http 端口' -ForegroundColor Red; exit 1 }
    $port = [int]$Matches[1]

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $port)
        Write-Host ("  listening  : 127.0.0.1:{0}" -f $port)
    }
    catch {
        Write-Host 'M1 FAIL: HTTP 端口不可连' -ForegroundColor Red
        exit 1
    }
    finally {
        $client.Close()
    }
}
finally {
    if ($null -ne $proc -and -not $proc.HasExited) { $proc.Kill(); $proc.WaitForExit() }
    Remove-Item $out -Force -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host 'M1 PASS' -ForegroundColor Green
