# Linux 服务端打包：模块 jar + 前端 + 启动脚本 -> zip（目标机需 JDK 25+）。
#
# 与 Windows 安装程序（package-windows.ps1）的关系：
# 本包部署在设备所在的 Linux 机器上，./start-server.sh 以 server 模式对外，
# 并托管 web/ 前端；Windows 端安装后在「连接服务器」里填本机地址即可远程操作。
param(
    [switch]$SkipJars,       # 复用各模块 target/*.jar
    [switch]$SkipFrontend    # 复用 frontend/dist
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$version = (Select-String -Path (Join-Path $root 'pom.xml') -Pattern '<version>([^<]+)</version>' |
    Select-Object -First 1).Matches.Groups[1].Value

# ---- 1. 前端产物（服务端 --web-root 托管，远程界面直接由服务端提供）----
if (-not $SkipFrontend) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-frontend.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'build-frontend.ps1 failed' }
}
$web = Join-Path $root 'frontend/dist'
if (-not (Test-Path (Join-Path $web 'index.html'))) {
    throw "缺少前端产物: $web（先运行 scripts/build-frontend.ps1，或去掉 -SkipFrontend）"
}

# ---- 2. 模块 jar（JVM 形态，无需原生镜像）----
if (-not $SkipJars) {
    Write-Host '--- maven jars ---'
    & mvn.cmd -B -q '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'maven package failed' }
}

$modules = 'secs-runtime','secs-api-http','secs-app','secs-core','secs-protocol','secs-json'
foreach ($module in $modules) {
    if (-not (Test-Path (Join-Path $root "$module/target/$module-$version.jar"))) {
        throw "缺少 $module-$version.jar（先构建，或去掉 -SkipJars）"
    }
}

# ---- 3. 组装（产物统一收到 build/installer）----
$packageDir = Join-Path $root 'build/installer'
New-Item -ItemType Directory -Force -Path $packageDir | Out-Null
$stage = Join-Path $packageDir "secs-sim-server-$version-linux"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'web') | Out-Null

foreach ($module in $modules) {
    # 目标名去掉版本号：start-server.sh 的 classpath 按 <module>.jar 引用，升级换版本不用改脚本
    Copy-Item (Join-Path $root "$module/target/$module-$version.jar") (Join-Path $stage "$module.jar")
}
Copy-Item "$web/*" (Join-Path $stage 'web') -Recurse
Copy-Item (Join-Path $root 'packaging/start-server.sh') $stage
Copy-Item (Join-Path $root 'packaging/README-linux.txt') (Join-Path $stage 'README.txt')
Copy-Item (Join-Path $root 'docs/guides/schema-v5.md') (Join-Path $stage 'docs-schema-v5.md')
Set-Content -Path (Join-Path $stage 'VERSION') -Value $version -Encoding ASCII
(Get-Content (Join-Path $stage 'README.txt') -Raw -Encoding UTF8).Replace('<版本>', $version) |
    Set-Content (Join-Path $stage 'README.txt') -Encoding UTF8 -NoNewline

# bash 语法自检（本机有 git-bash 即可）
$shCheck = & bash -n (Join-Path $stage 'start-server.sh') 2>&1
if ($LASTEXITCODE -ne 0) { throw "start-server.sh 语法错误:`n$shCheck" }
Write-Host 'start-server.sh syntax check OK'

$zip = Join-Path $packageDir "secs-sim-server-$version-linux.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $stage -DestinationPath $zip

Write-Host ''
Write-Host ("package : {0}" -f $stage)
Write-Host ("zip     : {0} ({1:N1} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))
Get-ChildItem $stage -Recurse -File |
    ForEach-Object { Write-Host ("  {0,-42} {1,8:N0} B" -f $_.FullName.Substring($stage.Length + 1), $_.Length) }
