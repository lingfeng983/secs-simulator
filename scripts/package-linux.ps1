# Linux 服务端打包：模块 jar + 前端 + 启动脚本 -> zip（目标机需 JDK 25+）。
#
# 与 Windows 安装程序（package-windows.ps1）的关系：
# 本包部署在设备所在的 Linux 机器上，./start-server.sh 以 server 模式对外，
# 并托管 web/ 前端；Windows 端安装后在「连接服务器」里填本机地址即可远程操作。
param(
    [switch]$SkipJars,       # 复用各模块 target/*.jar
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


$version = (Select-String -Path (Join-Path $root 'pom.xml') -Pattern '<version>([^<]+)</version>' |
    Select-Object -First 1).Matches.Groups[1].Value

# ---- 1. 前端产物（服务端 --web-root 托管，远程界面直接由服务端提供）----
if (-not $SkipFrontend) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-frontend.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'build-frontend.ps1 failed' }
}
$web = Join-Path $root 'frontend/dist'
if (-not (Test-Path (Join-Path $web 'index.html'))) {
    throw "缺少前端产物: $web（先运行 scripts/build-frontend.ps1，或去掉 -SkipFrontend）"
}

# ---- 2. 模块 jar（JVM 形态，无需原生镜像）----
if (-not $SkipJars) {
    Write-Host '--- maven jars ---'
    & mvn.cmd -B -q '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'maven package failed' }
}

# secs-cli 提供进程入口 CliMain，必须进包；classpath 顺序见 start-server.sh
# secs-store 是 3.0 的配置/运行态持久化层（config.db + runtime.db，ADR 0017），
# 漏了它打包脚本自己能跑通、启动时才 NoClassDefFoundError
$modules = 'secs-cli','secs-runtime','secs-api-http','secs-app','secs-store','secs-core','secs-protocol','secs-json'
foreach ($module in $modules) {
    if (-not (Test-Path (Join-Path $root "$module/target/$module-$version.jar"))) {
        throw "缺少 $module-$version.jar（先构建，或去掉 -SkipJars）"
    }
}

# ---- 2b. sqlite-jdbc（3.0 M0 / ADR 0017）：唯一 JNI 依赖，jar 进包进 classpath，
#         Linux 平台 .so 另放 native/ 供排障直接替换（JVM 下 sqlite-jdbc 会自解压）----
$pomText = Get-Content (Join-Path $root 'pom.xml') -Raw
if ($pomText -notmatch 'sqlite-jdbc</artifactId>\s*<version>([^<]+)</version>') {
    throw 'pom.xml 里找不到 sqlite-jdbc 的版本号（依赖管理段被改动了？）'
}
$sqliteVersion = $Matches[1]
$sqliteJar = Find-MavenArtifact "org/xerial/sqlite-jdbc/$sqliteVersion/sqlite-jdbc-$sqliteVersion.jar"
if ($null -eq $sqliteJar) { throw "缺少 sqlite-jdbc-$sqliteVersion.jar（先 mvn package 拉取依赖）" }

# ---- 3. 组装（产物统一收到 build/installer）----
$packageDir = Join-Path $root 'build/installer'
New-Item -ItemType Directory -Force -Path $packageDir | Out-Null
$stage = Join-Path $packageDir "secs-sim-server-$version-linux"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'web') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'thirdparty') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'native') | Out-Null

foreach ($module in $modules) {
    # 目标名去掉版本号：start-server.sh 的 classpath 按 <module>.jar 引用，升级换版本不用改脚本
    Copy-Item (Join-Path $root "$module/target/$module-$version.jar") (Join-Path $stage "$module.jar")
}
Copy-Item $sqliteJar (Join-Path $stage 'thirdparty/sqlite-jdbc.jar')
# 平台 .so 随包发布（3.0 M0）：JVM 下由 sqlite-jdbc 自解压，这份给排障/替换用
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sqliteZip = [System.IO.Compression.ZipFile]::OpenRead($sqliteJar)
try {
    $soEntry = $sqliteZip.Entries | Where-Object { $_.FullName -eq 'org/sqlite/native/Linux/x86_64/libsqlitejdbc.so' }
    if ($null -eq $soEntry) { throw "sqlite-jdbc jar 里没有 Linux 本地库（版本 $sqliteVersion）" }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile(
        $soEntry, (Join-Path $stage 'native/libsqlitejdbc.so'), $true)
} finally {
    $sqliteZip.Dispose()
}
Copy-Item "$web/*" (Join-Path $stage 'web') -Recurse
Copy-Item (Join-Path $root 'packaging/start-server.sh') $stage
Copy-Item (Join-Path $root 'packaging/README-linux.txt') (Join-Path $stage 'README.txt')
Copy-Item (Join-Path $root 'docs/guides/schema-v5.md') (Join-Path $stage 'docs-schema-v5.md')
Set-Content -Path (Join-Path $stage 'VERSION') -Value $version -Encoding ASCII
(Get-Content (Join-Path $stage 'README.txt') -Raw -Encoding UTF8).Replace('<版本>', $version) |
    Set-Content (Join-Path $stage 'README.txt') -Encoding UTF8 -NoNewline

# bash 语法自检（本机有 git-bash 即可）
$shCheck = & bash -n (Join-Path $stage 'start-server.sh') 2>&1
if ($LASTEXITCODE -ne 0) { throw "start-server.sh 语法错误:`n$shCheck" }
Write-Host 'start-server.sh syntax check OK'

$zip = Join-Path $packageDir "secs-sim-server-$version-linux.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $stage -DestinationPath $zip

Write-Host ''
Write-Host ("package : {0}" -f $stage)
Write-Host ("zip     : {0} ({1:N1} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))
Get-ChildItem $stage -Recurse -File |
    ForEach-Object { Write-Host ("  {0,-42} {1,8:N0} B" -f $_.FullName.Substring($stage.Length + 1), $_.Length) }
