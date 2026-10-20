# 构建 jpackage MSI（需要 WiX 3.x 的 candle.exe / light.exe）
param(
    [switch]$SkipJvmPackage,
    [string]$WixBin = ''
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$version = (Select-String -Path (Join-Path $root 'pom.xml') -Pattern '<version>([^<]+)</version>' |
    Select-Object -First 1).Matches.Groups[1].Value
$appVersion = ($version -replace '-.*$', '')
if ($appVersion -notmatch '^\d+\.\d+\.\d+$') {
    throw "jpackage 需要 x.y.z 版本，当前: $version"
}

$jvmStage = Join-Path $root "package/secs-sim-$version-jvm-win-x64"
if (-not $SkipJvmPackage) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts/package-jvm.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'JVM 包构建失败' }
}
if (-not (Test-Path (Join-Path $jvmStage 'runtime'))) {
    throw "缺少 JVM 包: $jvmStage（先运行 scripts/package-jvm.ps1，或去掉 -SkipJvmPackage）"
}

$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    throw '需要 JAVA_HOME 指向 JDK 25'
}
$jar = Join-Path $javaHome 'bin\jar.exe'
$jpackage = Join-Path $javaHome 'bin\jpackage.exe'
if (-not (Test-Path $jpackage)) { throw "缺少 jpackage: $jpackage" }

if ([string]::IsNullOrWhiteSpace($WixBin)) {
    if (-not [string]::IsNullOrWhiteSpace($env:WIX) -and (Test-Path (Join-Path $env:WIX 'bin\candle.exe'))) {
        $WixBin = Join-Path $env:WIX 'bin'
    } elseif (Test-Path (Join-Path $root 'target\wix\bin\candle.exe')) {
        $WixBin = Join-Path $root 'target\wix\bin'
    } else {
        $candle = Get-Command candle.exe -ErrorAction SilentlyContinue
        if ($candle) { $WixBin = Split-Path $candle.Source -Parent }
    }
}
if ([string]::IsNullOrWhiteSpace($WixBin) -or -not (Test-Path (Join-Path $WixBin 'candle.exe'))) {
    throw '缺少 WiX 3.x。安装 WiX 后把 bin 目录传给 -WixBin，或设置 WIX。下载: https://github.com/wixtoolset/wix3/releases'
}
$env:PATH = $WixBin + ';' + $env:PATH

$work = Join-Path $root 'target/jpackage'
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $work | Out-Null

$input = Join-Path $work 'input'
New-Item -ItemType Directory -Force -Path $input | Out-Null
Copy-Item (Join-Path $jvmStage 'lib\*.jar') $input
Copy-Item (Join-Path $jvmStage 'VERSION') $input
Copy-Item (Join-Path $root 'frontend/dist') (Join-Path $input 'web') -Recurse

$names = (Get-ChildItem $input -Filter *.jar | Select-Object -ExpandProperty Name)
$rest = 'Class-Path: ' + ($names -join ' ')
$lines = @('Manifest-Version: 1.0', 'Main-Class: com.shimizukenta.secssim.runtime.Main')
while ($rest.Length -gt 68) {
    $lines += $rest.Substring(0, 68)
    $rest = ' ' + $rest.Substring(68)
}
$lines += $rest
$manifest = Join-Path $work 'manifest.txt'
Set-Content -Path $manifest -Value $lines -Encoding ASCII
$launcher = Join-Path $input 'launcher.jar'
& $jar --create --file $launcher --manifest $manifest
if ($LASTEXITCODE -ne 0) { throw 'launcher jar 构建失败' }

$dest = Join-Path $work 'out'
New-Item -ItemType Directory -Force -Path $dest | Out-Null
$jpackageArgs = @(
    '--type', 'msi',
    '--name', 'SECS-Simulator',
    '--app-version', $appVersion,
    '--input', $input,
    '--main-jar', 'launcher.jar',
    '--main-class', 'com.shimizukenta.secssim.runtime.Main',
    '--runtime-image', (Join-Path $jvmStage 'runtime'),
    '--dest', $dest,
    '--vendor', 'SECS Simulator',
    '--description', 'SECS/GEM simulator',
    '--win-shortcut', '--win-menu', '--win-dir-chooser', '--win-console',
    '--arguments', '--demo-session --port 0 --hsms-port 5000 --web-root $APPDIR\web'
)
& $jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw 'jpackage MSI 构建失败' }

$msi = Get-ChildItem $dest -Filter *.msi | Select-Object -First 1
if ($null -eq $msi) { throw '没有生成 MSI' }
$target = Join-Path $root "package/secs-sim-$version-win-x64.msi"
Copy-Item $msi.FullName $target -Force

Write-Host ''
Write-Host ("msi : {0}" -f $target)
Write-Host ("size: {0:N1} MB" -f ((Get-Item $target).Length / 1MB))
Write-Host '注意：MSI 未签名；安装包只包含 demo 会话，生产配置需自行放到 work-dir。'
