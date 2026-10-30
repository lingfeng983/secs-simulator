$ErrorActionPreference = 'Continue'
$root = 'E:\ai\secs-simulator'
$work = Join-Path $env:TEMP ('secs-probe-' + [guid]::NewGuid().ToString('N').Substring(0,6))
New-Item -ItemType Directory -Path $work | Out-Null
$hsms = 15720
$out = Join-Path $work 'out.log'; $errf = Join-Path $work 'err.log'
$proc = Start-Process -FilePath (Join-Path $root 'secs-cli\target\secs-sim.exe') -ArgumentList @('--mode=integrated','--demo-session','--hsms-port',"$hsms",'--work-dir',$work) -PassThru -RedirectStandardOutput $out -RedirectStandardError $errf
$ready = Join-Path $work 'ready.txt'
$dl = (Get-Date).AddSeconds(60)
while (-not (Test-Path $ready)) { if ((Get-Date) -gt $dl) { break }; Start-Sleep -Milliseconds 150 }
if (-not (Test-Path $ready)) { 'NO READY'; Get-Content $out,$errf -Tail 20; Stop-Process -Id $proc.Id -Force; exit 1 }
'READY'
Set-Location (Join-Path $root 'scripts\host-e2e')
$cp = '..\..\secs-protocol\target\classes'
cmd /c "$env:JAVA_HOME\bin\javac.exe -cp $cp MinProbe.java > jc.log 2>&1"
'JAVAC=' + $LASTEXITCODE
if ($LASTEXITCODE -ne 0) { Get-Content jc.log }
if ($LASTEXITCODE -eq 0) {
  $env:CPJ = '.;' + $cp
  cmd /c "$env:JAVA_HOME\bin\java.exe -cp %CPJ% MinProbe $hsms 10 > run.log 2>&1"
  'RUN=' + $LASTEXITCODE
  Get-Content run.log
}
Set-Location $root
Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 200
'--- device stderr (tail) ---'
Get-Content $errf -Tail 12
Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue