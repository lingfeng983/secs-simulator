# 后端质量门禁：提交前必须全绿（原 check-all.ps1 的后端半边）。
#
# 依次执行：
#   1. mvn -B test              后端单元/集成测试
#   2. check-openapi.py         OpenAPI 契约静态校验（装了 Python 才跑）
#
# 前端门禁见 secs-simulator-ui/scripts/check.ps1；两者合起来替代原 scripts/check-all.ps1。
# 任一步失败立即以该步的退出码结束，不会带着已知失败继续往下跑。
param()

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$service = Split-Path $PSScriptRoot -Parent
Set-Location $service

Write-Host '=== 后端质量门禁 ==='
Write-Host '需要 JDK 25：请把 JAVA_HOME 指向 JDK 25（推荐 GraalVM 25），否则 mvn 会失败。'
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    Write-Warning 'JAVA_HOME 未设置：mvn 会用 PATH 里的 Java，版本不是 25 时会直接报错。'
} else {
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
    Write-Host "JAVA_HOME = $env:JAVA_HOME"
    if (Test-Path $java) {
        # java -version 把版本写到 stderr；在 $ErrorActionPreference='Stop' 下
        # 这会变成 NativeCommandError 直接终止脚本，所以这里临时放宽并捕获。
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            (& $java -version) 2>&1 | ForEach-Object { Write-Host "  $_" }
        } finally {
            $ErrorActionPreference = $previous
        }
    } else {
        Write-Warning "JAVA_HOME 下没有 bin/java.exe：$env:JAVA_HOME"
    }
}

function Invoke-Step {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Action
    )
    Write-Host ''
    Write-Host "--- $Name ---"
    & $Action
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        Write-Host ''
        Write-Host "✗ $Name 失败（exit $code），后续步骤不再执行。" -ForegroundColor Red
        exit $code
    }
}

Invoke-Step 'mvn -B test' { & mvn -B test }

# OpenAPI 重复键会让端点文档静默消失，属于"看不出来"的契约破坏；装了 Python 就查。
if (Get-Command python -ErrorAction SilentlyContinue) {
    Invoke-Step 'python check-openapi.py' { & python (Join-Path $PSScriptRoot 'check-openapi.py') }
} else {
    Write-Host ''
    Write-Host '--- openapi 校验 skipped（未找到 python）---'
}

Write-Host ''
Write-Host '✓ 后端门禁全部通过。' -ForegroundColor Green
