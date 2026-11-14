#requires -Version 5.1
<#
.SYNOPSIS
    界面文案门禁：产品界面只描述"能做什么 / 看到什么"，不出现实现词。

.DESCRIPTION
    设计 §3.5 的落地检查。扫描 src 下的 .ts/.tsx，只检查
    **用户能看到的文本**：
      - 字符串字面量（单引号 / 双引号 / 模板串）
      - JSX 文本（标签之间的中文）
    注释、import 路径、类型名、变量名、CSS 类名、接口路径一律不算。

    英文按词边界匹配；中文按"词"匹配（"表"只认 数据表/建表/表结构/表名/单表，
    不会误伤 列表/表格/报表/图表/仪表/表示/表达）。

    命中即失败，输出 `文件:行号: 命中的词 ← 该行文本`。

.PARAMETER Fix
    只报告不退出非零（本地排查用）。

.EXAMPLE
    pwsh scripts/check-ui-words.ps1
#>
param(
    [switch]$Fix
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $root 'src'))) {
    $root = (Get-Location).Path
}

# ---------------------------------------------------------------------------
# 白名单：放行的词 + 理由。
# 只放行"看起来像禁用词、实际是正常业务词"的写法；新增必须写清理由。
# ---------------------------------------------------------------------------
$whitelist = @(
    @{ Pattern = '列表';   Reason = '表格式列表是正常界面词，不是"数据表"' },
    @{ Pattern = '表格';   Reason = '同上' },
    @{ Pattern = '报表';   Reason = 'S6F11 采集报告的业务叫法' },
    @{ Pattern = '图表';   Reason = '曲线/趋势图' },
    @{ Pattern = '仪表';   Reason = '仪表盘读数' },
    @{ Pattern = '表单';   Reason = '表单是交互控件集合' },
    @{ Pattern = '表示';   Reason = '普通动词' },
    @{ Pattern = '表达';   Reason = '普通动词' },
    @{ Pattern = '表现';   Reason = '普通名词' },
    @{ Pattern = '表决';   Reason = '不涉及，但避免误伤' },
    @{ Pattern = '表面';   Reason = '普通名词' },
    @{ Pattern = '列表头'; Reason = '表格列标题的界面叫法' }
)

# 中文禁用词：按"词"而不是单字，避免把 列表/表格 全判成违规。
$bannedChinese = @(
    '数据库', '双库', '数据表', '建表', '表结构', '表名', '单表', '字段',
    '事务', '投影', '重投影', '迁移', '持久化', '落盘', '不写盘', '会话态',
    '幂等键', '快照', '乐观锁', '事件总线', '类名', '模块名', '执行器',
    '台账', '钉值', '写入磁盘', '写入数据库', '双写'
)

# 英文禁用词：词边界 + 大小写敏感匹配，避免命中 sqliteVersion 之类的标识符。
# 分两档：
#   $bannedEnglishUi   —— 整段只有它一个词也算违规（例如表头直接写 SHA-256）
#   $bannedEnglishCode —— 只有出现在中文句子里才算违规（列名/表名漏进说明文字）
$bannedEnglishUi = @(
    'SQLite', 'If-Revision', 'reproject', 'SHA256', 'SHA-256',
    'EventSource', 'hostId', 'SQL'
)
$bannedEnglishCode = @(
    'plan_execution', 'device_link', 'runtime_svid_pin', 'logs/messages\.jsonl'
)
$bannedEnglish = $bannedEnglishUi + $bannedEnglishCode

# 明确放行的英文（出现在 URL/协议名里不算界面文案），先剔除再判断。
$englishAllow = @(
    'BE\s*SSE'  # 占位：当前无
)

function Get-RelativePath([string]$path) {
    $full = [System.IO.Path]::GetFullPath($path)
    $base = [System.IO.Path]::GetFullPath($root)
    if ($full.StartsWith($base, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($base.Length).TrimStart('\', '/')
    }
    return $full
}

<#
    把文件内容拆成"候选文案"集合：字符串字面量 + JSX 文本（仅 .tsx）。
    注释与 import/export 行先抹成空格——**保留换行**，否则后面算出来的行号会往前漂。
#>
function Get-UiTexts([string]$source, [switch]$Jsx) {
    $text = $source

    # 块注释（含 JSDoc）：整段抹掉，只留换行
    $text = [regex]::Replace($text, '/\*[\s\S]*?\*/', { param($m) ($m.Value -replace '[^\r\n]', ' ') })
    # 行注释：前面不能是冒号（保住 http://）
    $text = [regex]::Replace($text, '(?<!:)//[^\r\n]*', '')
    # import / export ... from '...' 整行：模块路径不是文案
    $text = [regex]::Replace($text, '(?m)^\s*(import|export)\s[^\r\n]*?from\s+[''"][^''"]+[''"][^\r\n]*$',
        { param($m) ($m.Value -replace '[^\r\n]', ' ') })

    $results = New-Object System.Collections.Generic.List[object]

    # 1) 字符串字面量：'...' / "..." / `...`
    $stringPattern = @'
(?<quote>['"`])(?<body>(?:\\.|(?!\k<quote>)[\s\S])*?)\k<quote>
'@
    foreach ($match in [regex]::Matches($text, $stringPattern)) {
        $body = $match.Groups['body'].Value
        $line = ($text.Substring(0, $match.Index) -split "`n").Count
        $results.Add([pscustomobject]@{ Line = $line; Text = $body })
    }

    # 2) JSX 文本：> 与 < 之间的片段（是否算文案交给 Test-UiText 判断）。
    #    只在 .tsx 里做：.ts 的泛型与箭头函数会被误当成标签。
    if ($Jsx) {
        foreach ($match in [regex]::Matches($text, '>([^<>{}]+)<')) {
            $body = $match.Groups[1].Value
            if ($body.Trim() -ne '') {
                $line = ($text.Substring(0, $match.Index) -split "`n").Count
                $results.Add([pscustomobject]@{ Line = $line; Text = $body })
            }
        }
    }

    return $results
}

<# 判断一段候选文案是不是"用户可见文本"。 #>
function Test-UiText([string]$text) {
    $trimmed = $text.Trim()
    if ($trimmed -eq '') { return $false }

    # 纯标识符 / 路径 / CSS 类：没有空格与中文，且只含标识符字符
    if ($trimmed -notmatch '[\u4e00-\u9fa5\s]' -and $trimmed -match '^[A-Za-z0-9_$./:?#%&=@-]+$') {
        return $false
    }
    # 接口路径、hash 路由、URL
    if ($trimmed -match '^(/|#/|https?://)') { return $false }
    # 明确的代码片段特征
    if ($trimmed -match '^[a-z]+[A-Z][A-Za-z]*$') { return $false }   # camelCase 标识符
    if ($trimmed -match '^\.[A-Za-z]') { return $false }              # CSS 选择器

    return $true
}

<#
    模板串只在拼 key / 路由 / 选择器时不是文案：
    `host-zone:${hostId}:${next}` 去掉插值后只剩标点，没有中文也没有空格。
    没有插值的字符串一律当文案候选（裸标签 SQLite / logs/messages.jsonl 也要能抓到）。
#>
function Test-CodeShape([string]$text) {
    $trimmed = $text.Trim()
    if ($trimmed -notmatch '\$\{') { return $false }
    $rest = [regex]::Replace($trimmed, '\$\{[^}]*\}', '')
    if ($rest -match '[\u4e00-\u9fa5]') { return $false }
    if ($rest -match '\s') { return $false }
    return ($rest -match '^[A-Za-z0-9_$.:#/@%&=+-]*$')
}

<# 对一段文案跑禁用词，返回命中的词。 -BareOnly：裸标签只查"单词本身即违规"那一档。 #>
function Find-Banned([string]$text, [switch]$BareOnly) {
    $hits = New-Object System.Collections.Generic.List[string]

    if (-not $BareOnly) {
        foreach ($word in $bannedChinese) {
            if ($text.Contains($word)) {
                $allowed = $false
                foreach ($entry in $whitelist) {
                    if ($text.Contains($entry.Pattern) -and $entry.Pattern.Contains($word)) {
                        $allowed = $true
                    }
                }
                if (-not $allowed -and -not ($hits -contains $word)) {
                    $hits.Add($word)
                }
            }
        }
    }

    # -cmatch：大小写敏感，否则 '?sql=' 这类路径参数会命中 SQL
    foreach ($word in $bannedEnglishUi) {
        $pattern = if ($word -match '\\') { $word } else { "\b$([regex]::Escape($word))\b" }
        if ($text -cmatch $pattern -and -not ($hits -contains $word)) { $hits.Add($word) }
    }

    if (-not $BareOnly) {
        foreach ($word in $bannedEnglishCode) {
            $pattern = if ($word -match '\\') { $word } else { "\b$([regex]::Escape($word))\b" }
            if ($text -cmatch $pattern -and -not ($hits -contains $word)) { $hits.Add($word) }
        }
    }

    return $hits
}

$files = @()
$frontSrc = Join-Path $root 'src'
if (Test-Path $frontSrc) {
    $files += Get-ChildItem -Path $frontSrc -Recurse -File |
        Where-Object { $_.Extension -in '.ts', '.tsx' -and $_.Name -notlike '*.test.ts' -and $_.Name -notlike '*.test.tsx' }
}

$violations = New-Object System.Collections.Generic.List[object]
$scanned = 0
$checked = 0

foreach ($file in $files) {
    $source = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    if ($null -eq $source) { continue }
    $scanned += 1

    $texts = Get-UiTexts $source -Jsx:($file.Extension -eq '.tsx')

    foreach ($text in $texts) {
        $value = $text.Text

        # 先剔掉模板串拼出来的 key / 路由 / 选择器（`host-zone:${hostId}:${next}`）
        if (Test-CodeShape $value) { continue }

        # 注意：Find-Banned 命中 0 个时返回空集合，PowerShell 会把空集合摊平
        # 成 $null，所以这里用 @() 兜住，"扫过"和"有命中"要分开记。
        $candidate = $false
        $hits = @()
        if (Test-UiText $value) {
            $candidate = $true
            $hits = @(Find-Banned $value)
        } elseif ($value.Trim() -match '^[A-Za-z][A-Za-z0-9./_-]{0,40}$') {
            # 裸标签（<th>SQLite</th> 这种）：只查"这个词本身就是实现词"那一档
            $candidate = $true
            $hits = @(Find-Banned $value -BareOnly)
        }

        if (-not $candidate) { continue }
        $checked += 1
        if ($hits.Count -gt 0) {
            $snippet = ($text.Text -replace '\s+', ' ').Trim()
            if ($snippet.Length -gt 60) { $snippet = $snippet.Substring(0, 60) + '…' }
            $violations.Add([pscustomobject]@{
                File = (Get-RelativePath $file.FullName)
                Line = $text.Line
                Words = ($hits -join ',')
                Snippet = $snippet
            })
        }
    }
}

if ($violations.Count -eq 0) {
    Write-Host "[OK] 界面文案检查通过：$scanned 个文件 / $checked 段文案" -ForegroundColor Green
    exit 0
}

Write-Host "[FAIL] 界面文案出现实现词：$($violations.Count) 处（共 $scanned 个文件 / $checked 段文案）" -ForegroundColor Red
foreach ($violation in ($violations | Sort-Object File, Line)) {
    Write-Host ("  {0}:{1}: [{2}] {3}" -f $violation.File, $violation.Line, $violation.Words, $violation.Snippet)
}
Write-Host ''
Write-Host '改法：换成"用户能做的事/能看到的结果"。对照表见 docs/design/redesign-2026-11-12.md §3.5。'
if ($Fix) { exit 0 }
exit 1
