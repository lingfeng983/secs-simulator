# 打包 JVM 兜底包：应用 jar + jlink runtime + 前端 -> zip
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$version = (Select-String -Path (Join-Path $root 'pom.xml') -Pattern '<version>([^<]+)</version>' |
    Select-Object -First 1).Matches.Groups[1].Value

if (-not $SkipBuild) {
    Write-Host '--- application jars ---'
    & mvn.cmd -B -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'jar build failed' }
}

$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    $javaHome = Split-Path (Split-Path (Get-Command java).Source -Parent) -Parent
}
$javaExe = Join-Path $javaHome 'bin\java.exe'
$jlink = Join-Path $javaHome 'bin\jlink.exe'
$jdeps = Join-Path $javaHome 'bin\jdeps.exe'
if (-not (Test-Path $jlink)) { throw "缺少 jlink: $jlink（设置 JAVA_HOME 到 JDK 25）" }
if (-not (Test-Path $jdeps)) { throw "缺少 jdeps: $jdeps" }

$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersion = (& $javaExe -version 2>&1 | Out-String)
$ErrorActionPreference = $previousEap
$major = [regex]::Match($javaVersion, 'version "(\d+)').Groups[1].Value
if ($major -ne '25') {
    throw ("JVM 兜底包必须用 JDK 25（应用 class 版本 69），当前: " + $javaHome + [Environment]::NewLine + $javaVersion)
}

$web = Join-Path $root 'frontend/dist'
if (-not (Test-Path (Join-Path $web 'index.html'))) {
    throw "缺少前端产物: $web（先运行 scripts/build-frontend.ps1）"
}

$stage = Join-Path $root "package/secs-sim-$version-jvm-win-x64"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

$lib = Join-Path $stage 'lib'
New-Item -ItemType Directory -Force -Path $lib | Out-Null
$jars = Get-ChildItem (Join-Path $root 'secs-*\target\*.jar') |
    Where-Object { $_.Name -notlike 'secs-dist-*' -and $_.Name -notlike '*-sources.jar' }
if ($jars.Count -eq 0) { throw '没有找到应用 jar' }
$jars | ForEach-Object { Copy-Item $_.FullName $lib }

$deps = & $jdeps --multi-release 25 --ignore-missing-deps --print-module-deps ($jars | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($deps)) { throw 'jdeps 计算模块失败' }
Write-Host "jlink modules: $deps"

$runtime = Join-Path $stage 'runtime'
& $jlink --add-modules $deps --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output $runtime
if ($LASTEXITCODE -ne 0) { throw 'jlink 失败' }

Copy-Item $web (Join-Path $stage 'web') -Recurse
Copy-Item (Join-Path $root 'packaging/start-jvm.ps1') $stage
Copy-Item (Join-Path $root 'packaging/start-jvm.cmd') $stage
Copy-Item (Join-Path $root 'packaging/README-jvm.txt') $stage
Copy-Item (Join-Path $root 'docs/schema-v5.md') (Join-Path $stage 'docs-schema-v5.md')
Copy-Item (Join-Path $root 'packaging/update.ps1') $stage
Copy-Item (Join-Path $root 'packaging/check-update.cmd') $stage
Copy-Item (Join-Path $root 'packaging/README-update.txt') $stage
Copy-Item (Join-Path $root 'packaging/update-manifest.example.json') $stage
Set-Content -Path (Join-Path $stage 'VERSION') -Value $version -Encoding ASCII

$startCheck = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $stage 'start-jvm.ps1') -CheckOnly 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "start-jvm.ps1 路径自检失败:`n$($startCheck -join [Environment]::NewLine)"
}
Write-Host 'start-jvm.ps1 path check OK'

$javaCheck = & (Join-Path $stage 'runtime\bin\java.exe') -cp (Join-Path $stage 'lib\*') com.shimizukenta.secssim.runtime.Main --help 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "JVM 启动自检失败:`n$($javaCheck -join [Environment]::NewLine)"
}
Write-Host 'JVM classpath check OK'

$zip = Join-Path $root "package/secs-sim-$version-jvm-win-x64.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $stage -DestinationPath $zip

Write-Host ''
Write-Host ("package : {0}" -f $stage)
Write-Host ("zip     : {0} ({1:N1} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))
