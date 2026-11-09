$ErrorActionPreference = 'Continue'
# 本脚本位于 tools/host-e2e/，仓库根在上两级
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$work = Join-Path $env:TEMP ('secs-e2e-' + [guid]::NewGuid().ToString('N').Substring(0,6))
New-Item -ItemType Directory -Path $work | Out-Null
$hsms = 15730
$out = Join-Path $work 'out.log'
$errf = Join-Path $work 'err.log'
$exe = Join-Path $root 'secs-cli\target\secs-sim.exe'
$dist = Join-Path $root 'frontend\dist'
# 退出码：Start-Process 带 -RedirectStandardOutput 时 ExitCode 读不到（PowerShell 行为），
# 改由 cmd /c 转发子进程退出码，重定向在 cmd 内完成。
# 直接启动（不重定向）：Start-Process 带重定向时 ExitCode 读不到；日志本来就落在 work/logs。
$proc = Start-Process -FilePath $exe -ArgumentList @('--mode=integrated','--demo-session','--hsms-port',"$hsms",'--web-root',$dist,'--work-dir',$work) -PassThru -WindowStyle Hidden
$ready = Join-Path $work 'ready.txt'
$dl = (Get-Date).AddSeconds(90)
while (-not (Test-Path $ready)) { if ((Get-Date) -gt $dl) { break }; Start-Sleep -Milliseconds 150 }
if (-not (Test-Path $ready)) { 'NO READY'; Get-Content (Join-Path $work 'logs\secs-sim.log') -Tail 20 -ErrorAction SilentlyContinue; Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue; exit 1 }
$http = (Select-String -Path $ready -Pattern '^http=(\d+)').Matches[0].Groups[1].Value
'READY http=' + $http + ' hsms=' + $hsms

'==== 1/2 browser real E2E ===='
$env:E2E_REAL_URL = 'http://127.0.0.1:' + $http
Push-Location (Join-Path $root 'frontend')
cmd /c "npx playwright test -c playwright.real.config.ts > real.log 2>&1"
$script:pw = $LASTEXITCODE
Pop-Location
'PLAYWRIGHT=' + $script:pw
Get-Content (Join-Path $root 'frontend\real.log') -Tail 5 -ErrorAction SilentlyContinue

'==== 2/2 protocol host + HTTP full ===='
Set-Location (Join-Path $root 'tools\host-e2e')
$cp = '..\..\secs-protocol\target\classes;..\..\secs-json\target\classes'
cmd /c "$env:JAVA_HOME\bin\javac.exe -cp $cp SimHost.java > jc.log 2>&1"
$script:javac = $LASTEXITCODE
'JAVAC=' + $script:javac
if ($script:javac -ne 0) { Get-Content jc.log }
$script:simhost = -1
if ($script:javac -eq 0) {
  $env:CPJ = '.;' + $cp
  cmd /c "$env:JAVA_HOME\bin\java.exe -cp %CPJ% SimHost $http $hsms 10 > run.log 2>&1"
  $script:simhost = $LASTEXITCODE
  'SIMHOST=' + $script:simhost
  Get-Content run.log
}
Set-Location $root

$exited = $proc.WaitForExit(20000)
$null = $proc.WaitForExit()
$code = if ($exited) { $proc.ExitCode } else { 'n/a' }
'SHUTDOWN_EXITED=' + $exited + ' code=' + $code
if (-not $exited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
'--- device stderr (tail) ---'
Get-ChildItem (Join-Path $work 'logs') -ErrorAction SilentlyContinue | Select-Object -First 3 | ForEach-Object { $_.Name }
if ($script:pw -eq 0 -and $script:simhost -eq 0 -and $exited -and $code -eq 0) { 'RESULT=PASS' } else { 'RESULT=FAIL' }
Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue