# 打包运行时后端：原生 exe + server 启动脚本（不带界面）-> zip
#
# 与 package.ps1（完整安装包）的区别：这个包部署在"设备所在"的机器上，
# 不带 web/；界面来自完整安装包那台机器，用「连接设置」指向本机。
param(
    [switch]$SkipBuild,
    # 附加产出 JVM 版 Linux 包（目标机需 JDK 25+；不含原生镜像）
    [switch]$IncludeLinux
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$version = (Select-String -Path (Join-Path $root 'pom.xml') -Pattern '<version>([^<]+)</version>' |
    Select-Object -First 1).Matches.Groups[1].Value

$exe = Join-Path $root 'secs-cli/target/secs-sim.exe'

if (-not $SkipBuild) {
    Write-Host '--- native image ---'
    & mvn.cmd -B -q -Pnative -pl secs-cli -am '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'native build failed' }
}

if (-not (Test-Path $exe)) { throw "缺少原生镜像: $exe（先构建，或去掉 -SkipBuild）" }

$stage = Join-Path $root "package/secs-sim-runtime-$version"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

Copy-Item $exe (Join-Path $stage 'secs-sim.exe')
Copy-Item (Join-Path $root 'packaging/start-server.ps1') $stage
Copy-Item (Join-Path $root 'packaging/start-server.cmd') $stage
Copy-Item (Join-Path $root 'packaging/README-runtime.txt') (Join-Path $stage 'README.txt')
Copy-Item (Join-Path $root 'docs/schema-v5.md') (Join-Path $stage 'docs-schema-v5.md')
Set-Content -Path (Join-Path $stage 'VERSION') -Value $version -Encoding ASCII
(Get-Content (Join-Path $stage 'README.txt') -Raw -Encoding UTF8).Replace('<版本>', $version) |
    Set-Content (Join-Path $stage 'README.txt') -Encoding UTF8 -NoNewline

# 启动脚本自检：路径写错的话双击就直接报 exe 不存在（M9 踩过）
$check = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $stage 'start-server.ps1') -CheckOnly 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "start-server.ps1 路径自检失败:`n$($check -join [Environment]::NewLine)"
}
Write-Host 'start-server.ps1 path check OK'

$zip = Join-Path $root "package/secs-sim-runtime-$version-win-x64.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $stage -DestinationPath $zip

Write-Host ''
Write-Host ("package : {0}" -f $stage)
Write-Host ("zip     : {0} ({1:N1} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))
Get-ChildItem $stage -Recurse -File |
    ForEach-Object { Write-Host ("  {0,-42} {1,8:N0} B" -f $_.FullName.Substring($stage.Length + 1), $_.Length) }

# ----- Linux JVM 包（可选）：模块 jar + start-server.sh，目标机需 JDK 25+ -----
if ($IncludeLinux) {
    $linStage = Join-Path $root "package/secs-sim-runtime-$version-linux"
    if (Test-Path $linStage) { Remove-Item $linStage -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $linStage | Out-Null

    foreach ($module in 'secs-runtime','secs-api-http','secs-app','secs-core','secs-protocol','secs-json') {
        Copy-Item (Join-Path $root "$module/target/$module-$version.jar") `
            (Join-Path $linStage ($module + '.jar'))
    }
    Copy-Item (Join-Path $root 'packaging/start-server.sh') $linStage
    Copy-Item (Join-Path $root 'packaging/README-runtime.txt') (Join-Path $linStage 'README.txt')
    Copy-Item (Join-Path $root 'docs/schema-v5.md') (Join-Path $linStage 'docs-schema-v5.md')
    Set-Content -Path (Join-Path $linStage 'VERSION') -Value $version -Encoding ASCII

    # bash 语法自检（本机有 git-bash 即可）
    $shCheck = & bash -n (Join-Path $linStage 'start-server.sh') 2>&1
    if ($LASTEXITCODE -ne 0) { throw "start-server.sh 语法错误:`n$shCheck" }
    Write-Host 'start-server.sh syntax check OK'

    $linZip = Join-Path $root "package/secs-sim-runtime-$version-linux-x64.zip"
    if (Test-Path $linZip) { Remove-Item $linZip -Force }
    Compress-Archive -Path $linStage -DestinationPath $linZip
    Write-Host ("linux zip: {0} ({1:N1} MB)" -f $linZip, ((Get-Item $linZip).Length / 1MB))
}
