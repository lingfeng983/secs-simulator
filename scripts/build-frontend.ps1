# 构建 React 前端。默认用 PATH 里的 node/pnpm；DSH 环境可用 DSH_NODE / DSH_PNPM 覆盖。
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$node = if ($env:DSH_NODE) { $env:DSH_NODE } else { 'node' }
$pnpm = if ($env:DSH_PNPM) { $env:DSH_PNPM } else { 'pnpm' }

$root = Split-Path $PSScriptRoot -Parent
$frontend = Join-Path $root 'frontend'

Write-Host '--- install ---'
# pnpm 11 默认不执行依赖的构建脚本（供应链默认拒绝），esbuild 的 postinstall 只是校验本地二进制。
# 用作用域白名单显式放行，比 --dangerously-allow-all-builds 安全。
# 走 cmd.exe 是因为 PowerShell 5.1 对 node 启动的 .mjs 的退出码透传不可靠（会把 0 报成非 0）。
$install = '"' + $node + '" "' + $pnpm + '" --dir "' + $frontend + '" install --config.onlyBuiltDependencies=esbuild --reporter=append-only'
& cmd.exe /c $install
if ($LASTEXITCODE -ne 0) { throw "pnpm install failed (exit $LASTEXITCODE)" }

Write-Host '--- build ---'
$build = '"' + $node + '" "' + $pnpm + '" --dir "' + $frontend + '" run build'
& cmd.exe /c $build
if ($LASTEXITCODE -ne 0) { throw "vite build failed (exit $LASTEXITCODE)" }

$dist = Join-Path $frontend 'dist'
if (-not (Test-Path (Join-Path $dist 'index.html'))) { throw 'dist/index.html missing' }

Write-Host '--- dist ---'
Get-ChildItem $dist -Recurse -File |
    ForEach-Object { Write-Host ("  {0,-42} {1,8:N0} B" -f $_.FullName.Substring($dist.Length + 1), $_.Length) }
