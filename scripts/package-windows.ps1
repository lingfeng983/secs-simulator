# Windows 安装程序打包：Tauri（NSIS）。
#
# 产出一个完整的安装程序，装好后两种用法二选一：
#   1.「本机运行」：启动打包进去的 secs-sim.exe（原生镜像，无需 JVM），界面连本机；
#   2.「连接服务器」：不启动本机后端，直接加载 Linux jar 服务端（package-linux.ps1 产出）托管的页面。
param(
    [switch]$SkipNative,     # 复用 secs-cli/target/secs-sim.exe
    [switch]$SkipFrontend    # 复用 frontend/dist
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

# Maven 本地仓库路径不能写死 `%USERPROFILE%\.m2\repository`：很多机器在
# settings.xml 里把 localRepository 指到别处（本机就在 E:\Apache\apache-maven-3.6.3\m2），
# 写死会让打包脚本报“缺 jar”，而那个 jar 明明已经装好了。
#
# 注意 `$ErrorActionPreference='Stop'` 下原生命令往 stderr 写会变成
# NativeCommandError 直接抛，所以这里临时放宽并逐行过滤（mvn 的 [INFO]/[ERROR]
# 行不能当路径用）。
function Get-MavenRepoCandidates {
    $list = @()
    if ($env:MAVEN_REPO) { $list += $env:MAVEN_REPO }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & mvn.cmd -B -q -o help:evaluate '-Dexpression=settings.localRepository' '-DforceStdout' 2>&1
        foreach ($line in @($out)) {
            $text = ([string]$line).Trim()
            if ($text.Length -gt 0 -and -not $text.StartsWith('[') -and (Test-Path -LiteralPath $text)) {
                $list += $text
            }
        }
    } catch {
        # mvn 不可用时继续试默认位置
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($env:M2_HOME) { $list += (Join-Path $env:M2_HOME 'm2') }
    $list += (Join-Path $env:USERPROFILE '.m2/repository')
    return $list | Select-Object -Unique
}

# 在候选仓库里找某个依赖 jar；找不到返回 $null（调用方给出可读的报错）。
function Find-MavenArtifact {
    param([string]$RelativePath)
    foreach ($repo in Get-MavenRepoCandidates) {
        $candidate = Join-Path $repo $RelativePath
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

# 版本只有一个来源：pom.xml。tauri.conf.json / Cargo.toml 必须跟它是同一个字符串，
# 否则安装包文件名、应用版本与服务端 jar 会悄悄对不上（三处各写各的是最典型的漂移）。
$version = (Select-String -Path (Join-Path $root 'pom.xml') -Pattern '<version>([^<]+)</version>' |
    Select-Object -First 1).Matches.Groups[1].Value
if ([string]::IsNullOrWhiteSpace($version)) { throw '无法从 pom.xml 读到版本号' }

$confPath = Join-Path $root 'gui/src-tauri/tauri.conf.json'
$cargoPath = Join-Path $root 'gui/src-tauri/Cargo.toml'

$confVersion = (Get-Content $confPath -Raw | ConvertFrom-Json).version
if ($confVersion -ne $version) {
    throw "版本不一致: tauri.conf.json=$confVersion, pom.xml=$version（改 tauri.conf.json 的 version 与 pom 对齐）"
}

$cargoVersion = (Select-String -Path $cargoPath -Pattern '^version\s*=\s*"([^"]+)"' |
    Select-Object -First 1).Matches.Groups[1].Value
if ($cargoVersion -ne $version) {
    throw "版本不一致: gui/src-tauri/Cargo.toml=$cargoVersion, pom.xml=$version（改 Cargo.toml 的 package.version 与 pom 对齐）"
}

Write-Host "--- version: $version（pom.xml / tauri.conf.json / Cargo.toml 一致）---"

# ---- 1. 前端产物（安装程序内置，本机模式由 secs-sim.exe 托管）----
if (-not $SkipFrontend) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-frontend.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'build-frontend.ps1 failed' }
}
$web = Join-Path $root 'frontend/dist'
if (-not (Test-Path (Join-Path $web 'index.html'))) {
    throw "缺少前端产物: $web（先运行 scripts/build-frontend.ps1，或去掉 -SkipFrontend）"
}

# ---- 2. 原生镜像（本机模式的后端）----
$exe = Join-Path $root 'secs-cli/target/secs-sim.exe'
if (-not $SkipNative) {
    Write-Host '--- native image ---'
    & mvn.cmd -B -q -Pnative -pl secs-cli -am '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'native build failed' }
}
if (-not (Test-Path $exe)) { throw "缺少原生镜像: $exe（先构建，或去掉 -SkipNative）" }

# ---- 3. 填充 Tauri resources（前端经 frontendDist 内嵌进壳，resources 只需本机后端）----
$resources = Join-Path $root 'gui/src-tauri/resources'
if (Test-Path $resources) { Remove-Item $resources -Recurse -Force }
New-Item -ItemType Directory -Force -Path $resources | Out-Null

Copy-Item $exe (Join-Path $resources 'secs-sim.exe')

# sqlite-jdbc 平台本地库作为外部文件随包（3.0 M0 / ADR 0017）：
# exe 镜像内仍带一份用于自释放；这份放在旁边，排障时可直接替换验证
$pomText = Get-Content (Join-Path $root 'pom.xml') -Raw
if ($pomText -notmatch 'sqlite-jdbc</artifactId>\s*<version>([^<]+)</version>') {
    throw 'pom.xml 里找不到 sqlite-jdbc 的版本号（依赖管理段被改动了？）'
}
$sqliteVersion = $Matches[1]
$sqliteJar = Find-MavenArtifact "org/xerial/sqlite-jdbc/$sqliteVersion/sqlite-jdbc-$sqliteVersion.jar"
if ($null -eq $sqliteJar) { throw "缺少 sqlite-jdbc-$sqliteVersion.jar（先 mvn package 拉取依赖）" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sqliteZip = [System.IO.Compression.ZipFile]::OpenRead($sqliteJar)
try {
    $dllEntry = $sqliteZip.Entries | Where-Object { $_.FullName -eq 'org/sqlite/native/Windows/x86_64/sqlitejdbc.dll' }
    if ($null -eq $dllEntry) { throw "sqlite-jdbc jar 里没有 Windows 本地库（版本 $sqliteVersion）" }
    New-Item -ItemType Directory -Force -Path (Join-Path $resources 'native') | Out-Null
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile(
        $dllEntry, (Join-Path $resources 'native/sqlitejdbc.dll'), $true)
} finally {
    $sqliteZip.Dispose()
}

# ---- 4. tauri build（Rust release + NSIS 安装程序）----
Write-Host '--- tauri build ---'
Push-Location (Join-Path $root 'gui')
try {
    # install 幂等：node_modules 已就位时秒回；npm.CMD 由 PATH 解析，退出码直传
    & npm install --no-fund --no-audit
    if ($LASTEXITCODE -ne 0) { throw "npm install failed (exit $LASTEXITCODE)" }
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw "tauri build failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

# ---- 5. 收集产物 ----
$nsisDir = Join-Path $root 'gui/src-tauri/target/release/bundle/nsis'
$installer = Get-ChildItem $nsisDir -Filter '*-setup.exe' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $installer) { throw "NSIS 安装程序未找到: $nsisDir" }

# 文件名由 Tauri 用 tauri.conf.json 的 version 拼出来；对不上说明版本又漂了。
if ($installer.Name -notlike "*_${version}_*-setup.exe") {
    throw "安装包文件名里没有版本 ${version}: $($installer.Name)（检查 tauri.conf.json 的 version）"
}

# 产物统一收到 build/installer（build/ 已在 .gitignore 中）
$packageDir = Join-Path $root 'build/installer'
New-Item -ItemType Directory -Force -Path $packageDir | Out-Null
$dest = Join-Path $packageDir $installer.Name
Copy-Item $installer.FullName $dest -Force

Write-Host ''
Write-Host ("installer : {0} ({1:N1} MB)" -f $dest, ((Get-Item $dest).Length / 1MB))
Write-Host "内含: 桌面壳(前端内嵌) + secs-sim.exe(本机后端)"
