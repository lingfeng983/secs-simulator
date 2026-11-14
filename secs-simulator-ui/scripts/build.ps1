# 构建 React 前端（npm + vite）。默认用 PATH 里的 node/npm；DSH 环境可用 DSH_NODE / DSH_NPM 覆盖。
#
# 仓库只保留一套依赖树：package-lock.json。CI（.github/workflows/ci.yml）与
# scripts/check-all.ps1 同样走 npm，这里保持一致，不再混用 pnpm。
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$node = if ($env:DSH_NODE) { $env:DSH_NODE } else { 'node' }
$npm = if ($env:DSH_NPM) { $env:DSH_NPM } else { 'npm' }

$root = Split-Path $PSScriptRoot -Parent
$frontend = Join-Path $root 'frontend'
$dist = Join-Path $frontend 'dist'

function Invoke-Npm {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    if ($env:DSH_NPM) {
        # DSH 环境的 npm 是裸 js 脚本，须用 node 直启；
        # 走 cmd.exe 是因为 PowerShell 5.1 对 node 启动的 .mjs 退出码透传不可靠（会把 0 报成非 0）。
        $line = '"' + $node + '" "' + $npm + '" ' + ($Arguments -join ' ')
        & cmd.exe /c $line
    } else {
        & npm @Arguments
    }
    if ($LASTEXITCODE -ne 0) { throw "npm $($Arguments[0]) failed (exit $LASTEXITCODE)" }
}

Push-Location $frontend
try {
    Write-Host '--- install ---'
    # 缺依赖才安装：打包/门禁反复调用时不必每次重装；CI 用 npm ci 保证锁文件一致
    if (-not (Test-Path (Join-Path $frontend 'node_modules'))) {
        Invoke-Npm @('ci', '--no-fund', '--no-audit')
    } else {
        Write-Host 'npm ci: skipped（frontend/node_modules 已存在）'
    }

    Write-Host '--- build ---'
    Invoke-Npm @('run', 'build')
} finally {
    Pop-Location
}

if (-not (Test-Path (Join-Path $dist 'index.html'))) { throw 'dist/index.html missing' }

Write-Host '--- dist ---'
Get-ChildItem $dist -Recurse -File |
    ForEach-Object { Write-Host ("  {0,-42} {1,8:N0} B" -f $_.FullName.Substring($dist.Length + 1), $_.Length) }
