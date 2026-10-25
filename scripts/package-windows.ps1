# Windows 安装程序打包：Tauri（NSIS）。
#
# 产出一个完整的安装程序，装好后两种用法二选一：
#   1.「本机运行」：启动打包进去的 secs-sim.exe（原生镜像，无需 JVM），界面连本机；
#   2.「连接服务器」：不启动本机后端，直接加载 Linux jar 服务端（package-linux.ps1 产出）托管的页面。
param(
    [switch]$SkipNative,     # 复用 secs-cli/target/secs-sim.exe
    [switch]$SkipFrontend    # 复用 frontend/dist
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$version = (Select-String -Path (Join-Path $root 'pom.xml') -Pattern '<version>([^<]+)</version>' |
    Select-Object -First 1).Matches.Groups[1].Value

# ---- 1. 前端产物（安装程序内置，本机模式由 secs-sim.exe 托管）----
if (-not $SkipFrontend) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-frontend.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'build-frontend.ps1 failed' }
}
$web = Join-Path $root 'frontend/dist'
if (-not (Test-Path (Join-Path $web 'index.html'))) {
    throw "缺少前端产物: $web（先运行 scripts/build-frontend.ps1，或去掉 -SkipFrontend）"
}

# ---- 2. 原生镜像（本机模式的后端）----
$exe = Join-Path $root 'secs-cli/target/secs-sim.exe'
if (-not $SkipNative) {
    Write-Host '--- native image ---'
    & mvn.cmd -B -q -Pnative -pl secs-cli -am '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'native build failed' }
}
if (-not (Test-Path $exe)) { throw "缺少原生镜像: $exe（先构建，或去掉 -SkipNative）" }

# ---- 3. 填充 Tauri resources（bundle.resources 会整体打进安装包）----
$resources = Join-Path $root 'gui/src-tauri/resources'
if (Test-Path $resources) { Remove-Item $resources -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $resources 'web') | Out-Null

Copy-Item $exe (Join-Path $resources 'secs-sim.exe')
Copy-Item "$web/*" (Join-Path $resources 'web') -Recurse
Copy-Item (Join-Path $root 'docs/schema-v5.md') (Join-Path $resources 'docs-schema-v5.md')

# ---- 4. tauri build（Rust release + NSIS 安装程序）----
Write-Host '--- tauri build ---'
Push-Location (Join-Path $root 'gui')
try {
    # install 幂等：node_modules 已就位时秒回；npm.CMD 由 PATH 解析，退出码直传
    & npm install --no-fund --no-audit
    if ($LASTEXITCODE -ne 0) { throw "npm install failed (exit $LASTEXITCODE)" }
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw "tauri build failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

# ---- 5. 收集产物 ----
$nsisDir = Join-Path $root 'gui/src-tauri/target/release/bundle/nsis'
$installer = Get-ChildItem $nsisDir -Filter '*-setup.exe' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $installer) { throw "NSIS 安装程序未找到: $nsisDir" }

$packageDir = Join-Path $root 'package'
New-Item -ItemType Directory -Force -Path $packageDir | Out-Null
$dest = Join-Path $packageDir $installer.Name
Copy-Item $installer.FullName $dest -Force

Write-Host ''
Write-Host ("installer : {0} ({1:N1} MB)" -f $dest, ((Get-Item $dest).Length / 1MB))
Write-Host "内含: secs-sim.exe(本机后端) + web(前端) + launcher(本机运行/连接服务器)"
