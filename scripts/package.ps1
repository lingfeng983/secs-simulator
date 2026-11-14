# 安装包打包：全仓唯一入口。
#
#   -Target Windows  Tauri（NSIS）安装程序：桌面壳（前端内嵌）+ secs-sim.exe（原生镜像本机后端）
#   -Target Linux    Linux 服务端 zip：模块 jar + 前端 + start-server.sh（目标机需 JDK 25+）
#   -Target All      两个都打（默认）
#
# 产物统一放在 scripts/out/（.gitignore 已忽略）。装好后两种用法二选一：
#   1.「本机运行」：启动打包进去的 secs-sim.exe（原生镜像，无需 JVM），界面连本机；
#   2.「连接服务器」：不启动本机后端，直接加载 Linux 服务端 zip 托管的页面。
param(
    [ValidateSet('Windows', 'Linux', 'All')]
    [string]$Target = 'All',
    [switch]$SkipNative,     # Windows：复用 secs-cli/target/secs-sim.exe
    [switch]$SkipJars,       # Linux：复用各模块 target/*.jar
    [switch]$SkipFrontend    # 共用：复用 secs-simulator-ui/dist
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = Split-Path $PSScriptRoot -Parent
$service = Join-Path $root 'secs-simulator-service'
$ui = Join-Path $root 'secs-simulator-ui'
$gui = Join-Path $root 'secs-simulator-gui'
$outDir = Join-Path $PSScriptRoot 'out'

# Maven 本地仓库路径不能写死 `%USERPROFILE%\.m2\repository`：很多机器在
# settings.xml 里把 localRepository 指到别处（本机就在 E:\Apache\apache-maven-3.6.3\m2），
# 写死会让打包脚本报"缺 jar"，而那个 jar 明明已经装好了。
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

# 版本只有一个来源：secs-simulator-service/pom.xml。tauri.conf.json / Cargo.toml 必须跟它是
# 同一个字符串，否则安装包文件名、应用版本与服务端 jar 会悄悄对不上（三处各写各的是最典型的漂移）。
$version = (Select-String -Path (Join-Path $service 'pom.xml') -Pattern '<version>([^<]+)</version>' |
    Select-Object -First 1).Matches.Groups[1].Value
if ([string]::IsNullOrWhiteSpace($version)) { throw '无法从 pom.xml 读到版本号' }

$confPath = Join-Path $gui 'src-tauri/tauri.conf.json'
$cargoPath = Join-Path $gui 'src-tauri/Cargo.toml'

$confVersion = (Get-Content $confPath -Raw | ConvertFrom-Json).version
if ($confVersion -ne $version) {
    throw "版本不一致: tauri.conf.json=$confVersion, pom.xml=$version（改 tauri.conf.json 的 version 与 pom 对齐）"
}

$cargoVersion = (Select-String -Path $cargoPath -Pattern '^version\s*=\s*"([^"]+)"' |
    Select-Object -First 1).Matches.Groups[1].Value
if ($cargoVersion -ne $version) {
    throw "版本不一致: Cargo.toml=$cargoVersion, pom.xml=$version（改 Cargo.toml 的 package.version 与 pom 对齐）"
}

Write-Host "--- version: $version（pom.xml / tauri.conf.json / Cargo.toml 一致）---"

# ---- 前端产物（安装程序内置；Linux 包由服务端 --web-root 同源托管）----
function Build-FrontendIfNeeded {
    if (-not $SkipFrontend) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ui 'scripts/build.ps1')
        if ($LASTEXITCODE -ne 0) { throw 'build.ps1 failed' }
    }
    $web = Join-Path $ui 'dist'
    if (-not (Test-Path (Join-Path $web 'index.html'))) {
        throw "缺少前端产物: $web（先运行 secs-simulator-ui/scripts/build.ps1，或去掉 -SkipFrontend）"
    }
    return $web
}

# ---- Windows：Tauri（NSIS）安装程序 ----
function Invoke-WindowsPackage {
    Write-Host ''
    Write-Host '=== Windows 安装程序（Tauri / NSIS）==='
    $web = Build-FrontendIfNeeded

    # 原生镜像（本机模式的后端）
    $exe = Join-Path $service 'secs-cli\target\secs-sim.exe'
    if (-not $SkipNative) {
        Write-Host '--- native image ---'
        Push-Location $service
        try {
            & mvn.cmd -B -q -Pnative -pl secs-cli -am '-DskipTests' package
            if ($LASTEXITCODE -ne 0) { throw 'native build failed' }
        } finally {
            Pop-Location
        }
    }
    if (-not (Test-Path $exe)) { throw "缺少原生镜像: $exe（先构建，或去掉 -SkipNative）" }

    # 填充 Tauri resources（前端经 frontendDist 内嵌进壳，resources 只需本机后端）
    $resources = Join-Path $gui 'src-tauri\resources'
    if (Test-Path $resources) { Remove-Item $resources -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $resources | Out-Null

    Copy-Item $exe (Join-Path $resources 'secs-sim.exe')

    # sqlite-jdbc 平台本地库作为外部文件随包（3.0 M0 / ADR 0017）：
    # exe 镜像内仍带一份用于自释放；这份放在旁边，排障时可直接替换验证
    $pomText = Get-Content (Join-Path $service 'pom.xml') -Raw
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

    # tauri build（Rust release + NSIS 安装程序）
    Write-Host '--- tauri build ---'
    Push-Location $gui
    try {
        # install 幂等：node_modules 已就位时秒回；npm.CMD 由 PATH 解析，退出码直传
        & npm install --no-fund --no-audit
        if ($LASTEXITCODE -ne 0) { throw "npm install failed (exit $LASTEXITCODE)" }
        & npm run build
        if ($LASTEXITCODE -ne 0) { throw "tauri build failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }

    # 收集产物
    $nsisDir = Join-Path $gui 'src-tauri\target\release\bundle\nsis'
    $installer = Get-ChildItem $nsisDir -Filter '*-setup.exe' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $installer) { throw "NSIS 安装程序未找到: $nsisDir" }

    # 文件名由 Tauri 用 tauri.conf.json 的 version 拼出来；对不上说明版本又漂了。
    if ($installer.Name -notlike "*_${version}_*-setup.exe") {
        throw "安装包文件名里没有版本 ${version}: $($installer.Name)（检查 tauri.conf.json 的 version）"
    }

    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $dest = Join-Path $outDir $installer.Name
    Copy-Item $installer.FullName $dest -Force

    Write-Host ''
    Write-Host ("installer : {0} ({1:N1} MB)" -f $dest, ((Get-Item $dest).Length / 1MB))
    Write-Host "内含: 桌面壳(前端内嵌) + secs-sim.exe(本机后端)"
}

# ---- Linux：服务端 zip（模块 jar + 前端 + 启动脚本）----
function Invoke-LinuxPackage {
    Write-Host ''
    Write-Host '=== Linux 服务端包（jar + web + start-server.sh）==='
    $web = Build-FrontendIfNeeded

    # 模块 jar（JVM 形态，无需原生镜像）
    if (-not $SkipJars) {
        Write-Host '--- maven jars ---'
        Push-Location $service
        try {
            & mvn.cmd -B -q '-DskipTests' package
            if ($LASTEXITCODE -ne 0) { throw 'maven package failed' }
        } finally {
            Pop-Location
        }
    }

    # secs-cli 提供进程入口 CliMain，必须进包；classpath 顺序见 start-server.sh
    # secs-store 是 3.0 的配置/运行态持久化层（config.db + runtime.db，ADR 0017），
    # 漏了它打包脚本自己能跑通、启动时才 NoClassDefFoundError
    $modules = 'secs-cli','secs-runtime','secs-api-http','secs-app','secs-store','secs-core','secs-protocol','secs-json'
    foreach ($module in $modules) {
        if (-not (Test-Path (Join-Path $service "$module/target/$module-$version.jar"))) {
            throw "缺少 $module-$version.jar（先构建，或去掉 -SkipJars）"
        }
    }

    # sqlite-jdbc（3.0 M0 / ADR 0017）：唯一 JNI 依赖，jar 进包进 classpath，
    # Linux 平台 .so 另放 native/ 供排障直接替换（JVM 下 sqlite-jdbc 会自解压）
    $pomText = Get-Content (Join-Path $service 'pom.xml') -Raw
    if ($pomText -notmatch 'sqlite-jdbc</artifactId>\s*<version>([^<]+)</version>') {
        throw 'pom.xml 里找不到 sqlite-jdbc 的版本号（依赖管理段被改动了？）'
    }
    $sqliteVersion = $Matches[1]
    $sqliteJar = Find-MavenArtifact "org/xerial/sqlite-jdbc/$sqliteVersion/sqlite-jdbc-$sqliteVersion.jar"
    if ($null -eq $sqliteJar) { throw "缺少 sqlite-jdbc-$sqliteVersion.jar（先 mvn package 拉取依赖）" }

    # 组装
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $stage = Join-Path $outDir "secs-sim-server-$version-linux"
    if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
    New-Item -ItemType Directory -Force -Path (Join-Path $stage 'web') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $stage 'thirdparty') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $stage 'native') | Out-Null

    foreach ($module in $modules) {
        # 目标名去掉版本号：start-server.sh 的 classpath 按 <module>.jar 引用，升级换版本不用改脚本
        Copy-Item (Join-Path $service "$module/target/$module-$version.jar") (Join-Path $stage "$module.jar")
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
    Copy-Item (Join-Path $service 'packaging/start-server.sh') $stage
    Copy-Item (Join-Path $service 'packaging/README-linux.txt') (Join-Path $stage 'README.txt')
    Copy-Item (Join-Path $root 'docs/guides/schema-v5.md') (Join-Path $stage 'docs-schema-v5.md')
    Set-Content -Path (Join-Path $stage 'VERSION') -Value $version -Encoding ASCII
    (Get-Content (Join-Path $stage 'README.txt') -Raw -Encoding UTF8).Replace('<版本>', $version) |
        Set-Content (Join-Path $stage 'README.txt') -Encoding UTF8 -NoNewline

    # bash 语法自检（本机有 git-bash 即可）
    $shCheck = & bash -n (Join-Path $stage 'start-server.sh') 2>&1
    if ($LASTEXITCODE -ne 0) { throw "start-server.sh 语法错误:`n$shCheck" }
    Write-Host 'start-server.sh syntax check OK'

    $zip = Join-Path $outDir "secs-sim-server-$version-linux.zip"
    if (Test-Path $zip) { Remove-Item $zip -Force }
    Compress-Archive -Path $stage -DestinationPath $zip

    Write-Host ''
    Write-Host ("package : {0}" -f $stage)
    Write-Host ("zip     : {0} ({1:N1} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))
    Get-ChildItem $stage -Recurse -File |
        ForEach-Object { Write-Host ("  {0,-42} {1,8:N0} B" -f $_.FullName.Substring($stage.Length + 1), $_.Length) }
}

if ($Target -in 'Windows', 'All') { Invoke-WindowsPackage }
if ($Target -in 'Linux', 'All') { Invoke-LinuxPackage }

Write-Host ''
Write-Host ("✓ 打包完成，产物在 {0}" -f $outDir) -ForegroundColor Green
