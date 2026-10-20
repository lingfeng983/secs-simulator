# 组装 Windows 安装包：JVM 兜底包 + install/uninstall 脚本 -> zip
param(
    [switch]$SkipPackage
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$version = (Select-String -Path (Join-Path $root 'pom.xml') -Pattern '<version>([^<]+)</version>' |
    Select-Object -First 1).Matches.Groups[1].Value

$jvmStage = Join-Path $root "package/secs-sim-$version-jvm-win-x64"
if (-not $SkipPackage) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts/package-jvm.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'JVM 包构建失败' }
}
if (-not (Test-Path (Join-Path $jvmStage 'start-jvm.cmd'))) {
    throw "缺少 JVM 包: $jvmStage（先运行 scripts/package-jvm.ps1，或去掉 -SkipPackage）"
}

$stage = Join-Path $root "package/secs-sim-$version-win-x64-setup"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

$payload = Join-Path $stage 'payload'
New-Item -ItemType Directory -Force -Path $payload | Out-Null
Copy-Item (Join-Path $jvmStage '*') $payload -Recurse -Force
Copy-Item (Join-Path $root 'packaging/uninstall.ps1') $payload
Copy-Item (Join-Path $root 'packaging/install.ps1') $stage

$zip = Join-Path $root "package/secs-sim-$version-win-x64-setup.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $stage -DestinationPath $zip

Write-Host ''
Write-Host ("setup : {0}" -f $stage)
Write-Host ("zip   : {0} ({1:N1} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))
