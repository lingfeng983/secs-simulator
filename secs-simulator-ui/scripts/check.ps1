# 前端质量门禁：提交前必须全绿（原 check-all.ps1 的前端半边）。
#
# 依次执行：
#   1. npm ci                    仅在缺 node_modules 时安装
#   2. npx tsc --noEmit          类型检查
#   3. check-ui-words.ps1        界面文案门禁（设计 §3.5）
#   4. npm test                  Vitest 单元/组件测试
#   5. npx playwright test       Mock E2E（需要先装浏览器，见下）
#
# 后端门禁见 secs-simulator-service/scripts/check.ps1；两者合起来替代原 scripts/check-all.ps1。
# 任一步失败立即以该步的退出码结束，不会带着已知失败继续往下跑。
param(
    [switch]$SkipE2E        # 跳过 Playwright（浏览器没装、只想跑单测时用）
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$ui = Split-Path $PSScriptRoot -Parent
Push-Location $ui
try {
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

    if (-not (Test-Path (Join-Path $ui 'node_modules'))) {
        Invoke-Step 'npm ci（前端依赖）' { & npm ci }
    } else {
        Write-Host ''
        Write-Host '--- npm ci: skipped（node_modules 已存在）---'
    }
    Invoke-Step 'npx tsc --noEmit（类型检查）' { & npx tsc --noEmit }
    # 文案门禁用子进程跑：它自己会 exit 1，直接 & 会把整个门禁会话一起带走。
    Invoke-Step 'check-ui-words.ps1（界面文案）' {
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'check-ui-words.ps1')
    }
    Invoke-Step 'npm test（Vitest）' { & npm test }
    if (-not $SkipE2E) {
        Invoke-Step 'npx playwright test（Mock E2E）' { & npx playwright test }
    }
} finally {
    Pop-Location
}

Write-Host ''
Write-Host '✓ 前端门禁全部通过。' -ForegroundColor Green
