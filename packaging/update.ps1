# 检查/应用更新：读取 manifest，下载 zip，校验 SHA256，调用安装器。
param(
    [Parameter(Mandatory = $true)][string]$Manifest,
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'SECS Simulator'),
    [switch]$Apply,
    [switch]$Force,
    [switch]$NoShortcuts,
    [switch]$NoRegistry
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

function Get-VersionKey([string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) { return [version]'0.0.0' }
    $normalized = ($value.Trim() -replace '^[vV]', '' -replace '-.*$', '')
    return [version]$normalized
}

if ($Manifest -match '^https?://') {
    $info = Invoke-RestMethod -Uri $Manifest -TimeoutSec 30
} else {
    $info = Get-Content -Raw -Path $Manifest | ConvertFrom-Json
}

foreach ($field in @('version', 'url', 'sha256')) {
    if ([string]::IsNullOrWhiteSpace($info.$field)) {
        throw "更新清单缺少字段: $field"
    }
}

$currentFile = Join-Path $InstallDir 'VERSION'
$current = if (Test-Path $currentFile) { (Get-Content -Raw -Path $currentFile).Trim() } else { '0.0.0' }
$currentKey = Get-VersionKey $current
$nextKey = Get-VersionKey $info.version

if (-not $Force -and $nextKey -le $currentKey) {
    Write-Host "已是最新: $current"
    exit 0
}

Write-Host "当前: $current"
Write-Host "可用: $($info.version)"
if ($info.notes) { Write-Host "说明: $($info.notes)" }

if (-not $Apply) {
    Write-Host '仅检查，不下载。加 -Apply 应用更新。'
    exit 0
}

if (-not (Test-Path $InstallDir)) {
    throw "安装目录不存在: $InstallDir"
}

$temp = Join-Path $env:TEMP ('secs-update-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $temp | Out-Null
$zip = Join-Path $temp 'setup.zip'
$extract = Join-Path $temp 'extract'
$backup = $null

try {
    Write-Host "下载: $($info.url)"
    if ($info.url -match '^https?://') {
        Invoke-WebRequest -Uri $info.url -OutFile $zip -UseBasicParsing
    } else {
        Copy-Item -Path $info.url -Destination $zip -Force
    }
    $hash = (Get-FileHash -Path $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $info.sha256.ToLowerInvariant()) {
        throw "SHA256 不匹配: 期望 $($info.sha256)，实际 $hash"
    }

    Expand-Archive -Path $zip -DestinationPath $extract -Force
    $installer = Get-ChildItem -Path $extract -Recurse -Filter install.ps1 | Select-Object -First 1
    if ($null -eq $installer) { throw '更新包里没有 install.ps1' }

    $backup = "$InstallDir.bak"
    if (Test-Path $backup) { Remove-Item $backup -Recurse -Force }
    Move-Item -Path $InstallDir -Destination $backup

    try {
        $installArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $installer.FullName,
            '-InstallDir', $InstallDir, '-NoLaunch')
        if ($NoShortcuts) { $installArgs += '-NoShortcuts' }
        if ($NoRegistry) { $installArgs += '-NoRegistry' }
        $p = Start-Process -FilePath 'powershell.exe' -ArgumentList $installArgs -Wait -PassThru -NoNewWindow
        if ($p.ExitCode -ne 0) { throw "安装器退出码 $($p.ExitCode)" }

        $installedFile = Join-Path $InstallDir 'VERSION'
        if (-not (Test-Path $installedFile)) { throw '更新后缺少 VERSION' }
        $installed = (Get-Content -Raw -Path $installedFile).Trim()
        if ((Get-VersionKey $installed) -ne $nextKey -and -not $Force) {
            throw "更新后版本不符: 期望 $($info.version)，实际 $installed"
        }
        Remove-Item $backup -Recurse -Force
        $backup = $null
        Write-Host "更新完成: $installed"
    } catch {
        if (Test-Path $InstallDir) { Remove-Item $InstallDir -Recurse -Force }
        if ($backup -and (Test-Path $backup) -and -not (Test-Path $InstallDir)) {
            Move-Item -Path $backup -Destination $InstallDir
            $backup = $null
            Write-Host '已回滚到旧版本。'
        }
        throw
    }
} finally {
    if ($backup -and (Test-Path $backup) -and -not (Test-Path $InstallDir)) {
        Move-Item -Path $backup -Destination $InstallDir
    }
    Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue
}
