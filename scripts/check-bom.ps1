# 检查仓库里的 .ps1 是否带 UTF-8 BOM。
# 背景：PowerShell 5.1 会把无 BOM 的 UTF-8 按 ANSI 读，中文乱码/引号吃坏。
# 规则：含非 ASCII 字符的 .ps1 必须带 BOM；纯 ASCII 的不强制。
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

$bad = @()
Get-ChildItem -Path $root -Recurse -Filter *.ps1 -File |
    Where-Object { $_.FullName -notmatch '\\(node_modules|target|package|dist)\\' } |
    ForEach-Object {
        $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
        $hasBom = ($bytes.Length -ge 3) -and ($bytes[0] -eq 0xEF) -and ($bytes[1] -eq 0xBB) -and ($bytes[2] -eq 0xBF)
        $body = if ($hasBom -and $bytes.Length -gt 3) { $bytes[3..($bytes.Length - 1)] } else { $bytes }
        $hasRealContent = ($body.Where({ $_ -gt 127 }).Count -gt 0)

        if ($hasRealContent -and -not $hasBom) {
            $script:bad += $_.FullName
        }
    }

if ($bad.Count -gt 0) {
    Write-Host '以下 .ps1 含中文但没有 UTF-8 BOM（PowerShell 5.1 会按 ANSI 读坏）:'
    $bad | ForEach-Object { Write-Host "  $_" }
    exit 1
}

Write-Host ("BOM 检查通过 ({0} 个 .ps1)" -f @(Get-ChildItem -Path $root -Recurse -Filter *.ps1 -File |
    Where-Object { $_.FullName -notmatch '\\(node_modules|target|package|dist)\\' }).Count)