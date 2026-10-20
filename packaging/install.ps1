# 简易安装器：把 payload 复制到用户目录，创建快捷方式/卸载项。
param(
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'SECS Simulator'),
    [switch]$NoShortcuts,
    [switch]$NoRegistry,
    [switch]$NoLaunch
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$payload = Join-Path $PSScriptRoot 'payload'
if (-not (Test-Path (Join-Path $payload 'start-jvm.cmd'))) {
    throw "安装包损坏：缺少 payload/start-jvm.cmd"
}

if (Test-Path $InstallDir) {
    Remove-Item $InstallDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Copy-Item (Join-Path $payload '*') $InstallDir -Recurse -Force

$uninstall = Join-Path $InstallDir 'uninstall.ps1'
if (-not (Test-Path $uninstall)) {
    throw "安装包损坏：缺少 uninstall.ps1"
}

if (-not $NoShortcuts) {
    $shell = New-Object -ComObject WScript.Shell
    $target = Join-Path $InstallDir 'start-jvm.cmd'
    foreach ($folder in @([Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('Desktop'))) {
        if ([string]::IsNullOrWhiteSpace($folder)) { continue }
        $link = $shell.CreateShortcut((Join-Path $folder 'SECS Simulator.lnk'))
        $link.TargetPath = $target
        $link.WorkingDirectory = $InstallDir
        $link.Description = 'SECS Simulator'
        $link.Save()
    }
}

if (-not $NoRegistry) {
    $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\SECSSimulator'
    New-Item -Path $key -Force | Out-Null
    Set-ItemProperty -Path $key -Name DisplayName -Value 'SECS Simulator'
    Set-ItemProperty -Path $key -Name DisplayVersion -Value '2.0.0-SNAPSHOT'
    Set-ItemProperty -Path $key -Name Publisher -Value 'SECS Simulator'
    Set-ItemProperty -Path $key -Name InstallLocation -Value $InstallDir
    Set-ItemProperty -Path $key -Name UninstallString -Value ('powershell.exe -NoProfile -ExecutionPolicy Bypass -File "' + $uninstall + '"')
}

Write-Host "已安装到: $InstallDir"
Write-Host "启动: $(Join-Path $InstallDir 'start-jvm.cmd')"
if (-not $NoLaunch) {
    Start-Process (Join-Path $InstallDir 'start-jvm.cmd')
}
