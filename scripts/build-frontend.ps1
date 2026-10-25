# 构建 React 前端。默认用 PATH 里的 node/pnpm；DSH 环境可用 DSH_NODE / DSH_PNPM 覆盖。
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$node = if ($env:DSH_NODE) { $env:DSH_NODE } else { 'node' }
$pnpm = if ($env:DSH_PNPM) { $env:DSH_PNPM } else { 'pnpm' }

$root = Split-Path $PSScriptRoot -Parent
$frontend = Join-Path $root 'frontend'

# pnpm 11 默认不执行依赖的构建脚本（供应链默认拒绝），esbuild 的 postinstall 只是校验本地二进制。
# 用作用域白名单显式放行，比 --dangerously-allow-all-builds 安全。
$installArgs = @('--dir', $frontend, 'install', '--config.onlyBuiltDependencies=esbuild', '--reporter=append-only')
$buildArgs = @('--dir', $frontend, 'run', 'build')

Write-Host '--- install ---'
if ($env:DSH_PNPM) {
    # DSH 环境的 pnpm 是裸 js 脚本，须用 node 直启；
    # 走 cmd.exe 是因为 PowerShell 5.1 对 node 启动的 .mjs 退出码透传不可靠（会把 0 报成非 0）。
    $install = '"' + $node + '" "' + $pnpm + '" ' + ($installArgs -join ' ')
    & cmd.exe /c $install
} else {
    & pnpm @installArgs
}
if ($LASTEXITCODE -ne 0) { throw "pnpm install failed (exit $LASTEXITCODE)" }

Write-Host '--- build ---'
if ($env:DSH_PNPM) {
    $build = '"' + $node + '" "' + $pnpm + '" ' + ($buildArgs -join ' ')
    & cmd.exe /c $build
} else {
    & pnpm @buildArgs
}
if ($LASTEXITCODE -ne 0) { throw "vite build failed (exit $LASTEXITCODE)" }

$dist = Join-Path $frontend 'dist'
if (-not (Test-Path (Join-Path $dist 'index.html'))) { throw 'dist/index.html missing' }

Write-Host '--- dist ---'
Get-ChildItem $dist -Recurse -File |
    ForEach-Object { Write-Host ("  {0,-42} {1,8:N0} B" -f $_.FullName.Substring($dist.Length + 1), $_.Length) }
