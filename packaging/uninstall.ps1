# 卸载：删除快捷方式、注册表项和安装目录。
param(
    [switch]$NoShortcuts,
    [switch]$NoRegistry
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

if (-not $NoShortcuts) {
    foreach ($folder in @([Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('Desktop'))) {
        if ([string]::IsNullOrWhiteSpace($folder)) { continue }
        $link = Join-Path $folder 'SECS Simulator.lnk'
        if (Test-Path $link) { Remove-Item $link -Force }
    }
}

if (-not $NoRegistry) {
    $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\SECSSimulator'
    if (Test-Path $key) { Remove-Item $key -Recurse -Force }
}

Set-Location $env:TEMP
Remove-Item $root -Recurse -Force
