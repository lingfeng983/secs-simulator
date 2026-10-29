# 本地质量门禁：提交/发版前必须全绿的一组检查（仓库没有 CI，这就是质量闸门）。
#
# 依次执行：
#   1. mvn -B test                 后端单元/集成测试
#   2. npm ci                      仅在前端缺 node_modules 时安装
#   3. npx tsc --noEmit            前端类型检查
#   4. npm test                    Vitest 单元/组件测试
#   5. npx playwright test         Mock E2E（需要先装浏览器，见下）
#
# 任一步失败立即以该步的退出码结束，不会带着已知失败继续往下跑。
param(
    [switch]$SkipBackend,   # 只跑前端链路
    [switch]$SkipE2E        # 跳过 Playwright（浏览器没装、只想跑单测时用）
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

Write-Host '=== 本地质量门禁 ==='
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
if (-not $SkipE2E) {
    Write-Host 'Playwright 浏览器需要先装一次：npx playwright install chromium'
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

if (-not $SkipBackend) {
    Invoke-Step 'mvn -B test' { & mvn -B test }
}

# OpenAPI 重复键会让端点文档静默消失，属于"看不出来"的契约破坏；装了 Python 就查。
if (Get-Command python -ErrorAction SilentlyContinue) {
    Invoke-Step 'python scripts/check-openapi.py' { & python (Join-Path $root 'scripts/check-openapi.py') }
} else {
    Write-Host ''
    Write-Host '--- openapi 校验 skipped（未找到 python）---'
}

$frontend = Join-Path $root 'frontend'
Push-Location $frontend
try {
    if (-not (Test-Path (Join-Path $frontend 'node_modules'))) {
        Invoke-Step 'npm ci（前端依赖）' { & npm ci }
    } else {
        Write-Host ''
        Write-Host '--- npm ci: skipped（frontend/node_modules 已存在）---'
    }
    Invoke-Step 'npx tsc --noEmit（类型检查）' { & npx tsc --noEmit }
    Invoke-Step 'npm test（Vitest）' { & npm test }
    if (-not $SkipE2E) {
        Invoke-Step 'npx playwright test（Mock E2E）' { & npx playwright test }
    }
} finally {
    Pop-Location
}

Write-Host ''
Write-Host '✓ 质量门禁全部通过。' -ForegroundColor Green
