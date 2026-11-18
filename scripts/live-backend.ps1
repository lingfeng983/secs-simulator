# Live integration-test backend launcher (temp helper).
$root = 'E:/ai/secs-simulator'
$cp = (Get-Content (Join-Path $root 'secs-simulator-service/secs-cli/cp.txt') -Raw).Trim()
# 模块 target/classes 前置：cp.txt 里是 .m2 的已安装 jar（可能是旧版），
# classpath 顺序在前者优先，保证跑的永远是刚编译的代码
$mods = 'secs-api-http','secs-app','secs-cli','secs-core','secs-json','secs-protocol','secs-runtime','secs-store'
$modClasses = ($mods | ForEach-Object { Join-Path $root "secs-simulator-service/$_/target/classes" }) -join ';'
$full = $modClasses + ';' + (Join-Path $root 'secs-simulator-service/secs-cli/target/classes') + ';' + $cp
$work = Join-Path $root '.live-work'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$java = 'E:/Java/graalvm-jdk-25.0.4+7.1/bin/java.exe'
$progArgs = @(
  '-cp', $full,
  'com.shimizukenta.secssim.cli.CliMain',
  '--port', '56193',
  '--web-root', (Join-Path $root 'secs-simulator-ui/dist'),
  '--work-dir', $work,
  '--log-level', 'info'
)
Start-Process -FilePath $java -ArgumentList $progArgs `
  -RedirectStandardOutput (Join-Path $work 'out.log') `
  -RedirectStandardError (Join-Path $work 'err.log') `
  -WindowStyle Hidden -PassThru | Select-Object -ExpandProperty Id
