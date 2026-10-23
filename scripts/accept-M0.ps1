# M0 验收：多模块骨架 + 分层规则 + 编码基线 + native 风险验证
param([switch]$SkipNative)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

# JDK 25 由调用方通过 JAVA_HOME 提供；仅当环境里没设时才尝试 GraalVM 常见安装位置。
# 原来这里写死了某个人的本机路径，换台机器就跑不起来。
if (-not $env:JAVA_HOME) {
    $graal = 'C:\Program Files\Java\graalvm-jdk-25'
    if (Test-Path $graal) { $env:JAVA_HOME = $graal }
}
if (-not $env:JAVA_HOME) { Write-Host '需要 JDK 25：请先设置 JAVA_HOME' -ForegroundColor Red; exit 1 }

Set-Location (Join-Path $PSScriptRoot '..')

Write-Host '=== [1/4] JVM 构建与单元测试 ===' -ForegroundColor Cyan
& mvn.cmd -B -q verify
if ($LASTEXITCODE -ne 0) { Write-Host 'M0 FAIL: verify' -ForegroundColor Red; exit 1 }

Write-Host '=== [2/4] 原生镜像构建 ===' -ForegroundColor Cyan
if ($SkipNative) {
    Write-Host '  skipped'
} else {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    & mvn.cmd -B -q -Pnative -pl secs-cli -am '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { Write-Host 'M0 FAIL: native build' -ForegroundColor Red; exit 1 }
    $sw.Stop()
    $exe = Get-Item 'secs-cli/target/secs-smoke.exe'
    Write-Host ("  image       : {0} ({1:N1} MB)" -f $exe.Name, ($exe.Length / 1MB))
    Write-Host ("  build time  : {0:N1} s" -f $sw.Elapsed.TotalSeconds)

    Write-Host '=== [3/4] native 端到端冒烟（HSMS-SS 异步 NIO / HTTP / SSE / 中文路径）===' -ForegroundColor Cyan
    & mvn.cmd -B -q -pl secs-cli -am '-Dtest=NativeSmokeIT' '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { Write-Host 'M0 FAIL: native smoke' -ForegroundColor Red; exit 1 }

    Write-Host '=== [4/4] 冷启动时延（3 次取最小）===' -ForegroundColor Cyan
    $startup = @()
    foreach ($i in 1..3) {
        $port = (New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)).LocalEndpoint.Port
        $out = New-TemporaryFile
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $p = Start-Process -FilePath $exe.FullName -ArgumentList '--hsms-port', $port, '--work-dir', $env:TEMP -PassThru -RedirectStandardOutput $out -NoNewWindow
        while (-not ($p.HasExited)) {
            if ((Get-Content $out -Raw -ErrorAction SilentlyContinue) -match 'READY ') { break }
            Start-Sleep -Milliseconds 5
        }
        $sw.Stop()
        $startup += $sw.Elapsed.TotalMilliseconds
        $p.Kill(); $p.WaitForExit()
        Remove-Item $out -Force
    }
    Write-Host ("  startup     : {0:N0} ms (median of 3)" -f ($startup | Sort-Object)[1])
}

Write-Host ''
Write-Host 'M0 PASS' -ForegroundColor Green
