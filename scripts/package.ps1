# 打包：原生 exe + 前端产物 + 启动脚本 -> zip
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$version = (Select-String -Path (Join-Path $root 'pom.xml') -Pattern '<version>([^<]+)</version>' |
    Select-Object -First 1).Matches.Groups[1].Value

$exe = Join-Path $root 'secs-cli/target/secs-sim.exe'
$web = Join-Path $root 'frontend/dist'

if (-not $SkipBuild) {
    Write-Host '--- native image ---'
    & mvn.cmd -B -q -Pnative -pl secs-cli -am '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'native build failed' }
}

if (-not (Test-Path $exe)) { throw "缺少原生镜像: $exe（先构建，或去掉 -SkipBuild）" }
if (-not (Test-Path (Join-Path $web 'index.html'))) {
    throw "缺少前端产物: $web（先运行 scripts/build-frontend.ps1）"
}

$stage = Join-Path $root "package/secs-sim-$version"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

Copy-Item $exe (Join-Path $stage 'secs-sim.exe')
Copy-Item $web (Join-Path $stage 'web') -Recurse
Copy-Item (Join-Path $root 'packaging/start.ps1') $stage
Copy-Item (Join-Path $root 'packaging/start.cmd') $stage
Copy-Item (Join-Path $root 'packaging/README.txt') $stage
Copy-Item (Join-Path $root 'docs/schema-v5.md') (Join-Path $stage 'docs-schema-v5.md')
Set-Content -Path (Join-Path $stage 'VERSION') -Value $version -Encoding ASCII

# 包根启动脚本自检：M9 曾因 start.ps1 多退一级父目录，双击 start.cmd 直接报 exe 不存在。
$startCheck = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $stage 'start.ps1') -CheckOnly 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "start.ps1 路径自检失败:`n$($startCheck -join [Environment]::NewLine)"
}
Write-Host 'start.ps1 path check OK'

$zip = Join-Path $root "package/secs-sim-$version-win-x64.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $stage -DestinationPath $zip

Write-Host ''
Write-Host ("package : {0}" -f $stage)
Write-Host ("zip     : {0} ({1:N1} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))
Get-ChildItem $stage -Recurse -File |
    ForEach-Object { Write-Host ("  {0,-42} {1,8:N0} B" -f $_.FullName.Substring($stage.Length + 1), $_.Length) }
