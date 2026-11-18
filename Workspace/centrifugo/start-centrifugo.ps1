# 启动 Centrifugo 实时信令服务端（Windows 单二进制运行，无需 Docker）
$ErrorActionPreference = "Stop"

# 切换到脚本所在目录，保证 config.json 与 centrifugo.exe 都能被找到。
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

$Version     = "6.9.6"
$ExeName     = "centrifugo.exe"
$ZipName     = "centrifugo_${Version}_windows_amd64.zip"
$DownloadUrl = "https://github.com/centrifugal/centrifugo/releases/download/v$Version/$ZipName"
$ConfigFile  = "config.json"

# 缺少配置文件时直接报错，避免用默认配置裸跑（那样密钥与命名空间都不是联调要的值）。
if (-not (Test-Path $ConfigFile)) {
    throw "未找到配置文件 $ConfigFile，请确认它位于 $ProjectRoot 目录下。"
}

# 二进制缺失时自动下载并解压。
if (-not (Test-Path $ExeName)) {
    Write-Host "未找到 $ExeName，开始下载 Centrifugo v$Version ..." -ForegroundColor Cyan
    Write-Host "下载地址：$DownloadUrl"

    # GitHub 要求 TLS 1.2，而 Windows PowerShell 5.1 默认协议可能仍偏旧。
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $ProgressPreference = "SilentlyContinue"

    try {
        Invoke-WebRequest -Uri $DownloadUrl -OutFile $ZipName -UseBasicParsing
    }
    catch {
        throw "下载 Centrifugo 失败：$($_.Exception.Message)`n请检查网络或代理设置，也可手动下载 $ZipName，解压出 $ExeName 后放到 $ProjectRoot 目录下再重试。"
    }

    # 校验下载产物确实落地，避免把半截文件当成成功。
    if (-not (Test-Path $ZipName)) {
        throw "下载未生成 $ZipName，请检查网络或代理设置。"
    }

    Write-Host "下载完成，正在解压 ..." -ForegroundColor Cyan

    # 压缩包内还带有 README.md / CHANGELOG.md / LICENSE。
    # 若直接解压到当前目录会覆盖本项目自己的 README.md，
    # 因此先解压到临时子目录，只取走可执行文件。
    $ExtractDir = Join-Path $ProjectRoot "__extract_tmp"
    if (Test-Path $ExtractDir) {
        Remove-Item $ExtractDir -Recurse -Force
    }

    try {
        Expand-Archive -Path $ZipName -DestinationPath $ExtractDir -Force
    }
    catch {
        throw "解压 $ZipName 失败：$($_.Exception.Message)`n请确认该文件是完整的 zip 压缩包（注意不是 .tar.gz）。"
    }

    $ExtractedExe = Join-Path $ExtractDir $ExeName
    if (-not (Test-Path $ExtractedExe)) {
        throw "压缩包内未找到 $ExeName，请确认下载的是 windows_amd64 版本。"
    }

    Move-Item -Path $ExtractedExe -Destination (Join-Path $ProjectRoot $ExeName) -Force
    Remove-Item $ExtractDir -Recurse -Force

    # 解压后再校验一次，确认可执行文件确实就位。
    if (-not (Test-Path $ExeName)) {
        throw "解压后仍未找到 $ExeName，请手动解压 $ZipName 并把 $ExeName 放到 $ProjectRoot 目录下。"
    }

    Write-Host "Centrifugo v$Version 已就绪。" -ForegroundColor Green
}

Write-Host "启动 Centrifugo：$ExeName --config=$ConfigFile" -ForegroundColor Green
Write-Host "信令服务监听：http://localhost:8000"
Write-Host "健康检查：http://localhost:8000/health"
Write-Host "坐席端不直连 8000 端口，需经前端 dev server 的 /centrifugo/* 反向代理接入。"
Write-Host "按 Ctrl + C 停止服务。"

& ".\$ExeName" "--config=$ConfigFile"

if ($LASTEXITCODE -ne 0) {
    Write-Host "Centrifugo 已退出，退出码 $LASTEXITCODE，请检查上方日志。" -ForegroundColor Yellow
}
