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

# 版本只有一个来源：secs-simulator-service/pom.xml。tauri.conf.json / Cargo.toml / 两个 package.json
# 必须跟它是同一个字符串，否则安装包文件名、应用版本与服务端 jar 会悄悄对不上（各写各的是最典型的漂移）。
# pom 解析限定顶层 project/version（第一个 <version> 可能是 parent 或依赖的版本，正则脆弱——评审 P3）。
$pomXml = [xml](Get-Content (Join-Path $service 'pom.xml') -Raw)
$version = $pomXml.project.version
if ([string]::IsNullOrWhiteSpace($version)) { throw '无法从 pom.xml 读到顶层 project/version' }

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

# 两个 package.json 也纳入校验（评审 P3：此前不在同步链里，npm 包版本会悄悄漂移）
foreach ($pkg in @((Join-Path $ui 'package.json'), (Join-Path $gui 'package.json'))) {
    $pkgVersion = (Get-Content $pkg -Raw | ConvertFrom-Json).version
    if ($pkgVersion -ne $version) {
        throw "版本不一致: $pkg=$pkgVersion, pom.xml=$version（改 package.json 的 version 与 pom 对齐）"
    }
}
Write-Host "--- version: ui/gui package.json 亦一致 ---"

# ---- 前端产物（安装程序内置；Linux 包由服务端 --web-root 同源托管）----
function Build-FrontendIfNeeded {
    if (-not $SkipFrontend) {
        # 子进程用 Start-Process -Wait 跑，输出直接进控制台、不进管道：
        # 早先写成 `$web = & powershell.exe ... -File build.ps1`，build.ps1 里 Write-Host
        # 的每一行（"--- install ---"、vite 输出、dist 清单…）都被当成函数返回值收进 $web，
        # 于是 `Copy-Item "$web/*"` 被拼成 "--- install --- npm ci …\dist/*"，
        # 报错"名为'--- install ---'的驱动器不存在"。函数的返回值只允许是路径一条。
        $build = Start-Process -FilePath 'powershell.exe' `
            -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $ui 'scripts/build.ps1')) `
            -NoNewWindow -Wait -PassThru
        if ($build.ExitCode -ne 0) { throw "build.ps1 failed (exit $($build.ExitCode))" }
    }
    $web = Join-Path $ui 'dist'
    if (-not (Test-Path (Join-Path $web 'index.html'))) {
        throw "缺少前端产物: $web（先运行 secs-simulator-ui/scripts/build.ps1，或去掉 -SkipFrontend）"
    }
    if ($SkipFrontend) {
        Test-ArtifactFreshness -Artifact (Join-Path $web 'index.html') -SourceDir (Join-Path $ui 'src') -Label '前端 dist'
    }
    return $web
}

# ---- Skip 开关的产物新鲜度检查：只警告，不阻断 ----
# -SkipNative / -SkipFrontend 只查产物存在，同版本旧产物会悄悄进包（dim08 评审 P2）。
# 这里拿产物 mtime 对比「git HEAD 提交时间」与「源码目录最新 mtime」的较大者；
# 产物更旧就 Write-Warning 提示可能是旧代码，仍继续打包（冒烟/门禁兜运行正确性）。
function Test-ArtifactFreshness {
    param([string]$Artifact, [string]$SourceDir, [string]$Label)
    if (-not (Test-Path $Artifact)) { return }  # 存在性由各调用方负责
    try {
        $baseline = [datetime]::MinValue
        # git HEAD 时间：失败（无 git / 非仓库）就只靠源码 mtime
        $head = & git -C $root log -1 --format=%ct 2>$null
        if ($LASTEXITCODE -eq 0 -and "$head" -match '^\d+$') {
            $baseline = [DateTimeOffset]::FromUnixTimeSeconds([long]"$head").LocalDateTime
        }
        if (Test-Path $SourceDir) {
            $newest = Get-ChildItem $SourceDir -Recurse -File -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -notmatch '[\\/](target|node_modules|dist)[\\/]' } |
                Sort-Object LastWriteTime -Descending | Select-Object -First 1
            if ($newest -and $newest.LastWriteTime -gt $baseline) { $baseline = $newest.LastWriteTime }
        }
        $mtime = (Get-Item $Artifact).LastWriteTime
        if ($baseline -gt [datetime]::MinValue -and $mtime -lt $baseline) {
            Write-Warning "$Label 可能陈旧：产物 $Artifact（$mtime）早于源码/最近提交（$baseline）。建议去掉对应 -Skip 开关重建。"
        }
    } catch {
        Write-Warning "无法校验 $Label 新鲜度（$($_.Exception.Message)），继续打包。"
    }
}

# ---- 原生镜像冒烟：exe 必须能自己起来并回 JSON 健康检查 ----
#
# 只"打得出来"不代表"装完能用"：native-image 一旦漏了 classpath 资源（seed/*.json），
# exe 会在播种内置模板那一步直接退出，桌面壳拿不到后端地址，界面只剩「后端不可达」，
# 而安装包看上去完全正常。所以打包前用临时工作目录真跑一遍：等到 READY、
# 再用 HTTP 取一次 /api/v1/health，确认回的是 JSON 而不是别的东西。
function Test-NativeBackend {
    param([Parameter(Mandatory = $true)][string]$Exe)

    $work = Join-Path ([System.IO.Path]::GetTempPath()) ('secs-native-smoke-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    $outFile = Join-Path $work 'stdout.txt'
    $errFile = Join-Path $work 'stderr.txt'

    # Git Bash 启动的 PowerShell 会同时带 Path/PATH/path 三个键，PS 5.1 的
    # Start-Process 构造子进程环境字典时按大小写敏感判重，直接抛
    #「已添加项。字典中的关键字:"Path"」。这里先合并成一个键再启动。
    foreach ($name in @([Environment]::GetEnvironmentVariables().Keys)) {
        if ($name -ieq 'path' -and $name -cne 'Path') {
            [Environment]::SetEnvironmentVariable($name, $null)
        }
    }

    $process = Start-Process -FilePath $Exe `
        -ArgumentList @('--mode=integrated', '--port=0', "--work-dir=$work") `
        -RedirectStandardOutput $outFile -RedirectStandardError $errFile `
        -PassThru -WindowStyle Hidden
    try {
        $deadline = (Get-Date).AddSeconds(90)
        $ready = $null
        while ((Get-Date) -lt $deadline) {
            if ($process.HasExited) { break }
            # 重定向文件刚建出来时是空的，Get-Content -Raw 对空文件返回 $null，
            # 直接喂给 [regex]::Match 会抛"值不能为 null"——用拼接兜成空串
            $text = '' + (Get-Content $outFile -Raw -Encoding UTF8 -ErrorAction SilentlyContinue)
            $ready = [regex]::Match($text, 'READY[^\r\n]*http=(\d+)')
            if ($ready.Success) { break }
            Start-Sleep -Milliseconds 300
        }
        if ($null -eq $ready -or -not $ready.Success) {
            $detail = if (Test-Path $errFile) { (Get-Content $errFile -Tail 25 -Encoding UTF8) -join "`n" } else { '' }
            throw "原生后端冒烟失败：90 秒内没有 READY（后端起不来的安装包就是坏包）`n$detail"
        }
        $port = $ready.Groups[1].Value
        $health = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 -Uri "http://127.0.0.1:$port/api/v1/health"
        if ($health.Content -notmatch '"ok"\s*:\s*true') {
            throw "原生后端冒烟失败：/api/v1/health 没回 ok=true（$($health.Content)）"
        }
    } finally {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Milliseconds 300
        Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Host 'native smoke OK（READY + /api/v1/health）'
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
            # ErrorActionPreference='Stop' 下，原生命令往 stderr 写一行（JDK 25 的
            # restricted / terminally deprecated 警告、npm WARN 之类）都会被 PS 5.1
            # 当成 NativeCommandError 直接抛，整套打包在第一步就断。临时放宽、按退出码判成败。
            $prevPref = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                & mvn.cmd -B -q -Pnative -pl secs-cli -am '-DskipTests' package 2>&1 |
                    ForEach-Object { Write-Host $_ }
                if ($LASTEXITCODE -ne 0) { throw 'native build failed' }
            } finally {
                $ErrorActionPreference = $prevPref
            }
        } finally {
            Pop-Location
        }
    }
    if (-not (Test-Path $exe)) { throw "缺少原生镜像: $exe（先构建，或去掉 -SkipNative）" }
    if ($SkipNative) {
        Test-ArtifactFreshness -Artifact $exe -SourceDir $service -Label '原生镜像 secs-sim.exe'
    }

    # 冒烟放在拷贝进 resources 之前：坏 exe 在这里就拦住，不用等 tauri build 跑完
    Test-NativeBackend -Exe $exe

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
        $prevPref = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & npm install --no-fund --no-audit 2>&1 | ForEach-Object { Write-Host $_ }
            if ($LASTEXITCODE -ne 0) { throw "npm install failed (exit $LASTEXITCODE)" }
            & npm run build 2>&1 | ForEach-Object { Write-Host $_ }
            if ($LASTEXITCODE -ne 0) { throw "tauri build failed (exit $LASTEXITCODE)" }
        } finally {
            $ErrorActionPreference = $prevPref
        }
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
            $prevPref = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                & mvn.cmd -B -q '-DskipTests' package 2>&1 | ForEach-Object { Write-Host $_ }
                if ($LASTEXITCODE -ne 0) { throw 'maven package failed' }
            } finally {
                $ErrorActionPreference = $prevPref
            }
        } finally {
            Pop-Location
        }
    }

    # secs-cli 提供进程入口 CliMain，必须进包；classpath 顺序见 start-server.sh
    # secs-store 是 3.0 的配置/运行态持久化层（config.db + runtime.db，ADR 0017），
    # 漏了它打包脚本自己能跑通、启动时才 NoClassDefFoundError
    $modules = 'secs-cli','secs-runtime','secs-api-http','secs-app','secs-store','secs-core','secs-protocol','secs-json'
    foreach ($module in $modules) {
        $jar = Join-Path $service "$module/target/$module-$version.jar"
        if (-not (Test-Path $jar)) {
            throw "缺少 $module-$version.jar（先构建，或去掉 -SkipJars）"
        }
        if ($SkipJars) {
            Test-ArtifactFreshness -Artifact $jar -SourceDir $service -Label "模块 jar $module"
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
    Copy-Item (Join-Path $service 'scripts/packaging/start-server.sh') $stage
    Copy-Item (Join-Path $service 'scripts/packaging/README-linux.txt') (Join-Path $stage 'README.txt')
    # 配置格式说明随包发布。文件名带 schema 版本，升版（v5 → v7）时这里必须跟着改：
    # 漏改的话前面全绿，最后一步 Copy-Item 才报"找不到 schema-v5.md"，白等一次打包。
    $schemaDoc = Join-Path $root 'docs/guides/schema-v7.md'
    if (-not (Test-Path $schemaDoc)) {
        $found = Get-ChildItem (Join-Path $root 'docs/guides') -Filter 'schema-*.md' -ErrorAction SilentlyContinue |
            ForEach-Object { $_.Name }
        throw "缺少配置格式说明: $schemaDoc（docs/guides 里现有: $($found -join ', ')）"
    }
    Copy-Item $schemaDoc (Join-Path $stage 'docs-schema-v7.md')
    Set-Content -Path (Join-Path $stage 'VERSION') -Value $version -Encoding ASCII
    (Get-Content (Join-Path $stage 'README.txt') -Raw -Encoding UTF8).Replace('<版本>', $version) |
        Set-Content (Join-Path $stage 'README.txt') -Encoding UTF8 -NoNewline

    # bash 语法自检（本机有 git-bash 即可）：只是顺手检查，没有 bash 就跳过——
    # 早先直接 `& bash -n ...`，PATH 里没 bash 的机器会以 CommandNotFoundException
    # 把整套打包打断，而这时包其实已经组装完了。
    $bash = Get-Command bash -ErrorAction SilentlyContinue
    if ($null -ne $bash) {
        $prevPref = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $shCheck = & $bash.Source -n (Join-Path $stage 'start-server.sh') 2>&1
            if ($LASTEXITCODE -ne 0) { throw "start-server.sh 语法错误:`n$shCheck" }
        } finally {
            $ErrorActionPreference = $prevPref
        }
        Write-Host 'start-server.sh syntax check OK'
    } else {
        Write-Warning 'PATH 里没有 bash，跳过 start-server.sh 语法自检（本次未改该脚本可忽略）'
    }

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
