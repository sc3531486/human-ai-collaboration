# 一键全量编译与启动脚本：Centrifugo(8000) → 微服务集群(8084, 8082, 8081, 8083, 8080) → Vue3 前端(5174, 5175)
param(
    [switch]$SkipBuild,
    [switch]$Windowed,
    [switch]$Verify,
    [switch]$KeepAlive
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$WorkspaceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $WorkspaceRoot

$LogDir = Join-Path $WorkspaceRoot "logs"
if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
}

$PidFile = Join-Path $WorkspaceRoot ".service-pids.json"
$PidsMap = @{}
$existingProcessRecords=$null
if(Test-Path -LiteralPath $PidFile){try{$existingProcessRecords=Get-Content -LiteralPath $PidFile -Raw -Encoding UTF8 | ConvertFrom-Json}catch{}}


# 8082 留给信令服务；业务内部回调使用独立回环端口，浏览器仍访问 HTTPS 8080。
$env:ROUTING_BUSINESS_BASE_URL="http://127.0.0.1:8090"
$env:BUSINESS_INTERNAL_HTTP_PORT="8090"
$env:BUSINESS_BASE_URL="http://127.0.0.1:8090"
$env:BUSINESS_CAPABILITIES_BASE_URL=$env:BUSINESS_BASE_URL
$env:BUSINESS_MEDIA_BASE_URL="http://127.0.0.1:8084"
$env:BUSINESS_MESSAGE_BASE_URL="http://127.0.0.1:8085"

# ------------------------------------------------------------------ 辅助函数

function Test-Port {
    param([int]$Port)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect("127.0.0.1", $Port)
        return $true
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

function Wait-Port {
    param([int]$Port, [int]$TimeoutSeconds, [string]$Name, [Diagnostics.Process]$Process, [string]$OutputLog, [string]$ErrorLog)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $ticks = 0
    while ((Get-Date) -lt $deadline) {
        if($Process){
            $Process.Refresh()
            if($Process.HasExited){
                Write-Host "[启动失败] $Name 已退出，退出码 $($Process.ExitCode)。" -ForegroundColor Red
                foreach($logFile in @($OutputLog,$ErrorLog)){
                    if($logFile -and (Test-Path -LiteralPath $logFile)){
                        Get-Content -LiteralPath $logFile -Encoding UTF8 -Tail 30 | ForEach-Object {Write-Host $_}
                    }
                }
                return $false
            }
        }
        if (Test-Port -Port $Port) { return $true }
        Start-Sleep -Milliseconds 500
        $ticks += 1
        if ($ticks % 20 -eq 0) {
            Write-Host "    ... 已等待 $([int]($ticks / 2)) 秒 ($Name)" -ForegroundColor DarkGray
        }
    }
    Write-Host "    [超时] $Name 在 $TimeoutSeconds 秒内未就绪 (端口 $Port)！" -ForegroundColor Red
    return $false
}

function Get-LanIPv4 {
    # 仅取有默认网关且正在工作的物理网卡；排除回环、APIPA 与 VPN 常见的无网关地址。
    $result = [System.Collections.Generic.List[string]]::new()
    [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
        Where-Object {
            $_.OperationalStatus -eq 'Up' -and
            $_.NetworkInterfaceType -in @('Wireless80211', 'Ethernet') -and
            $_.GetIPProperties().GatewayAddresses.Count -gt 0
        } | ForEach-Object {
            $_.GetIPProperties().UnicastAddresses | ForEach-Object {
                $ip = $_.Address
                if ($ip.AddressFamily -eq 'InterNetwork' -and
                    -not $ip.ToString().StartsWith('127.') -and
                    -not $ip.ToString().StartsWith('169.254.') -and
                    -not $result.Contains($ip.ToString())) {
                    $result.Add($ip.ToString())
                }
            }
        }
    return $result
}

function Save-ServiceProcess {
    param([string]$Name,[int]$ProcessId)
    $serviceProcess=Get-Process -Id $ProcessId -ErrorAction Stop
    $script:PidsMap[$Name]=@{
        pid=$ProcessId
        processName=$serviceProcess.ProcessName
        startedAtUtc=$serviceProcess.StartTime.ToUniversalTime().ToString("o")
    }
    $script:PidsMap | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $PidFile -Encoding UTF8
}

function Start-ServiceDirect {
    param(
        [string]$Name,
        [int]$Port,
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$WorkingDirectory,
        [int]$TimeoutSeconds = 60
    )

    Write-Host "`n>> 正在启动 [$Name] (端口 $Port) ..." -ForegroundColor Cyan
    if (Test-Port -Port $Port) {
        Write-Host "  $Name 端口 $Port 已在监听中，跳过重复启动。" -ForegroundColor DarkGray
        foreach($line in (netstat -ano -p tcp)) {
            if($line -match "^\s*TCP\s+\S+:$Port\s+\S+\s+LISTENING\s+(\d+)\s*$"){
                $existingProcessId=[int]$Matches[1]
                $existingProcess=Get-Process -Id $existingProcessId -ErrorAction SilentlyContinue
                $verified=$false
                $previousRecord=if($existingProcessRecords){$existingProcessRecords.$Name}else{$null}
                if($existingProcess -and $previousRecord -and $previousRecord.startedAtUtc){
                    try{$verified=[int]$previousRecord.pid -eq $existingProcessId -and $previousRecord.processName -eq $existingProcess.ProcessName -and ([DateTime]::Parse($previousRecord.startedAtUtc).ToUniversalTime() -eq $existingProcess.StartTime.ToUniversalTime())}catch{}
                }
                if(-not $verified){
                    try{
                        $command=[string](Get-CimInstance Win32_Process -Filter "ProcessId=$existingProcessId" -ErrorAction Stop).CommandLine
                        $verified=$command.IndexOf($WorkspaceRoot,[StringComparison]::OrdinalIgnoreCase) -ge 0
                        if(-not $verified -and [IO.Path]::IsPathRooted($FilePath)){$verified=$command.IndexOf($FilePath,[StringComparison]::OrdinalIgnoreCase) -ge 0}
                    }catch{}
                }
                if($verified){Save-ServiceProcess -Name $Name -ProcessId $existingProcessId}
                else{Write-Warning "端口 $Port 的现有进程归属未能核实，未加入停用清单。"}
                break
            }
        }
        return $true
    }

    $sanitized = ($Name -replace '[^a-zA-Z0-9_\-]','_')
    $outLog = Join-Path $LogDir "${sanitized}.out.log"
    $errLog = Join-Path $LogDir "${sanitized}.err.log"

    if ($Windowed) {
        $cmdStr = "$FilePath " + ($ArgumentList -join " ")
        $proc = Start-Process -FilePath "powershell.exe" `
            -ArgumentList "-NoLogo -NoProfile -ExecutionPolicy Bypass -NoExit -Command `"`$host.UI.RawUI.WindowTitle = '[$Name] :$Port'; Set-Location -LiteralPath '$WorkingDirectory'; & $cmdStr`"" `
            -PassThru
        Save-ServiceProcess -Name $Name -ProcessId $proc.Id
    } else {
        $proc = Start-Process -FilePath $FilePath `
            -ArgumentList $ArgumentList `
            -WorkingDirectory $WorkingDirectory `
            -RedirectStandardOutput $outLog `
            -RedirectStandardError $errLog `
            -WindowStyle Hidden `
            -PassThru
        Save-ServiceProcess -Name $Name -ProcessId $proc.Id
    }

    if (Wait-Port -Port $Port -TimeoutSeconds $TimeoutSeconds -Name $Name -Process $proc -OutputLog $outLog -ErrorLog $errLog) {
        Write-Host "  [$Name] 启动就绪 (PID: $($proc.Id))" -ForegroundColor Green
        return $true
    } else {
        Write-Host "  [$Name] 启动失败，请检查日志：$errLog" -ForegroundColor Red
        throw "服务启动失败：$Name（端口 $Port）"
    }
}

# ------------------------------------------------------------------ 1. 前置依赖检查

Write-Host "`n========================================================" -ForegroundColor Cyan
Write-Host "     远程视频银行 · 编译构建与全量微服务集群启动" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

foreach ($tool in @("java", "mvn", "node")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "未找到系统命令 [$tool]，请确认已安装并配置至 PATH 环境变量中。"
    }
}

if (-not (Test-Port -Port 3306)) {
    Write-Host "[警告] 本机 MySQL 3306 端口未监听！workflow-svc 与 business-svc 依赖 MySQL，启动可能会失败。" -ForegroundColor Yellow
} else {
    Write-Host "✓ 本机 MySQL 数据库已在线 (3306)" -ForegroundColor Green
}

# 首次使用缺少构建产物时，快速启动自动切换到构建。
if($SkipBuild){
    $modules=@("signaling-svc","media-svc","workflow-svc","routing-svc","business-svc")
    if(Test-Path (Join-Path $WorkspaceRoot "message-svc/pom.xml")){$modules+="message-svc"}
    foreach($module in $modules){
        if(-not(Test-Path (Join-Path $WorkspaceRoot "$module/target/$module-0.0.1-SNAPSHOT.jar"))){
            Write-Host "缺少 $module 构建产物，改为构建后启动。" -ForegroundColor Yellow
            $SkipBuild=$false;break
        }
    }
}

# ------------------------------------------------------------------ 2. 编译与打包

if (-not $SkipBuild) {
    Write-Host "`n--------------------------------------------------------" -ForegroundColor DarkGray
    Write-Host "[1/7] 全模块 Maven 编译与可执行 JAR 打包 ..." -ForegroundColor Cyan
    Write-Host "--------------------------------------------------------" -ForegroundColor DarkGray

    mvn clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 多模块打包构建失败，启动中止！"
    }
    $messagePom=Join-Path $WorkspaceRoot "message-svc/pom.xml"
    if(Test-Path $messagePom){mvn -f $messagePom package -DskipTests; if($LASTEXITCODE -ne 0){throw "消息服务打包失败"}}
    Write-Host "✓ 后端 5 个微服务已成功打包为 Spring Boot 可执行 JAR" -ForegroundColor Green

    Write-Host "`n[2/7] 前端独立交付物构建（视频控件、渠道 SDK、企业网银、坐席端）..." -ForegroundColor Cyan
    foreach($webProject in @("video-widget","channel-web-sdk","enterprise-demo","agent-web")) {
        Push-Location (Join-Path $WorkspaceRoot $webProject)
        try { npm run build; if($LASTEXITCODE -ne 0){throw "$webProject 构建失败"} }
        finally { Pop-Location }
    }
    Write-Host "✓ Vue3 坐席端、渠道 SDK、企业网银演示端与独立视频控件已构建" -ForegroundColor Green
} else {
    Write-Host "`n跳过构建阶段（使用既有编译产物）" -ForegroundColor DarkGray
}

# ------------------------------------------------------------------ 3. 启动 Centrifugo 信令服务 (8000)

$centrifugoDir = Join-Path $WorkspaceRoot "centrifugo"
if (-not (Test-Path $centrifugoDir)) {
    $altCentrifugo = Join-Path (Split-Path -Parent $WorkspaceRoot) "flowable-poc\centrifugo"
    if (Test-Path $altCentrifugo) {
        $centrifugoDir = $altCentrifugo
    }
}

$centrifugoExe = Join-Path $centrifugoDir "centrifugo.exe"
if (-not (Test-Path $centrifugoExe)) {
    throw "未找到 Centrifugo 可执行文件：$centrifugoExe"
}

Start-ServiceDirect -Name "Centrifugo信令引擎" -Port 8000 `
    -FilePath $centrifugoExe -ArgumentList @("--config=config.json") `
    -WorkingDirectory $centrifugoDir -TimeoutSeconds 30

$messageJar=Join-Path $WorkspaceRoot "message-svc/target/message-svc-0.0.1-SNAPSHOT.jar"
# 冷启动（刚打包完、磁盘缓存未热）时 Spring 上下文初始化可达 40s 以上，整体就绪曾实测 72s；
# 60s 会在服务其实已经起来的情况下误判超时并中止后续服务，故放宽到 180s。
if(Test-Path $messageJar){Start-ServiceDirect -Name "message-svc消息服务" -Port 8085 -FilePath "java" -ArgumentList @("-jar",$messageJar) -WorkingDirectory $WorkspaceRoot -TimeoutSeconds 180}

# ------------------------------------------------------------------ 4. 启动微服务集群

# (1) media-svc (8084)
$mediaJar = Join-Path $WorkspaceRoot "media-svc\target\media-svc-0.0.1-SNAPSHOT.jar"
Start-ServiceDirect -Name "media-svc音视频房间" -Port 8084 `
    -FilePath "java" -ArgumentList @("-jar", $mediaJar) `
    -WorkingDirectory $WorkspaceRoot -TimeoutSeconds 60

# (2) signaling-svc (8082)
$signalingJar = Join-Path $WorkspaceRoot "signaling-svc\target\signaling-svc-0.0.1-SNAPSHOT.jar"
Start-ServiceDirect -Name "signaling-svc信令服务" -Port 8082 `
    -FilePath "java" -ArgumentList @("-jar", $signalingJar) `
    -WorkingDirectory $WorkspaceRoot -TimeoutSeconds 60

# (3) routing-svc (8081)
$routingJar = Join-Path $WorkspaceRoot "routing-svc\target\routing-svc-0.0.1-SNAPSHOT.jar"
Start-ServiceDirect -Name "routing-svc排队路由" -Port 8081 `
    -FilePath "java" -ArgumentList @("-jar", $routingJar) `
    -WorkingDirectory $WorkspaceRoot -TimeoutSeconds 60

# (4) workflow-svc (8083) - 冷启动需初始化 Flowable 数据库表与引擎
$workflowJar = Join-Path $WorkspaceRoot "workflow-svc\target\workflow-svc-0.0.1-SNAPSHOT.jar"
Start-ServiceDirect -Name "workflow-svc流程引擎" -Port 8083 `
    -FilePath "java" -ArgumentList @("-jar", $workflowJar) `
    -WorkingDirectory $WorkspaceRoot -TimeoutSeconds 180

# (5) business-svc (8080, HTTPS)
$businessJar = Join-Path $WorkspaceRoot "business-svc\target\business-svc-0.0.1-SNAPSHOT.jar"
Start-ServiceDirect -Name "business-svc业务中枢" -Port 8080 `
    -FilePath "java" -ArgumentList @("-jar", $businessJar, "--spring.config.additional-location=optional:file:./business-svc/config/llm.local.properties") `
    -WorkingDirectory $WorkspaceRoot -TimeoutSeconds 120

# ------------------------------------------------------------------ 5. 启动新 Vue3 前端

# 仅停止命令行明确属于本工作区旧 navis-web 的进程，避免端口被旧页面占住后脚本误报成功。
function Stop-LegacyNavisFrontend {
    foreach($port in @(5173,5174)) {
        foreach($line in (netstat -ano -p tcp)) {
            if($line -match "^\s*TCP\s+\S+:$port\s+\S+\s+LISTENING\s+(\d+)\s*$") {
                $existingId=[int]$Matches[1]
                try {
                    $command=[string](Get-CimInstance Win32_Process -Filter "ProcessId=$existingId" -ErrorAction Stop).CommandLine
                    if($command.IndexOf((Join-Path $WorkspaceRoot 'navis-web\server.mjs'),[StringComparison]::OrdinalIgnoreCase) -ge 0) {
                        Stop-Process -Id $existingId -Force -ErrorAction Stop
                        Write-Host "已停止旧 navis-web 前端 (端口 $port, PID $existingId)。" -ForegroundColor Yellow
                    }
                } catch { }
                break
            }
        }
    }
}

Stop-LegacyNavisFrontend
$agentWebDir = Join-Path $WorkspaceRoot "agent-web"
$enterpriseDir = Join-Path $WorkspaceRoot "enterprise-demo"
node (Join-Path $agentWebDir "scripts/copy-video-widget-dist.mjs")
node (Join-Path $enterpriseDir "scripts/copy-sdk-dist.mjs")

$previousWebPort=$env:PORT
$previousHttps=$env:VITE_HTTPS
$previousBackend=$env:BACKEND_URL
$previousWidgetBase=$env:VITE_VIDEO_WIDGET_BASE
$previousTlsPassword=$env:TLS_PFX_PASSWORD
try {
    $env:VITE_HTTPS="true"
    $env:BACKEND_URL="https://localhost:8080"
    $env:VITE_VIDEO_WIDGET_BASE="/video-widget/"
    $env:TLS_PFX_PASSWORD="password"
    $env:PORT="5174"
    Start-ServiceDirect -Name "agent-web Vue3坐席端" -Port 5174 -FilePath "node" -ArgumentList @((Join-Path $agentWebDir "node_modules/vite/bin/vite.js"),"--host","0.0.0.0","--port","5174") -WorkingDirectory $agentWebDir -TimeoutSeconds 45
    $env:PORT="5175"
    Start-ServiceDirect -Name "enterprise-demo Vue3渠道端" -Port 5175 -FilePath "node" -ArgumentList @((Join-Path $enterpriseDir "node_modules/vite/bin/vite.js"),"--host","0.0.0.0","--port","5175") -WorkingDirectory $enterpriseDir -TimeoutSeconds 45
} finally {
    $env:PORT=$previousWebPort
    $env:VITE_HTTPS=$previousHttps
    $env:BACKEND_URL=$previousBackend
    $env:VITE_VIDEO_WIDGET_BASE=$previousWidgetBase
    $env:TLS_PFX_PASSWORD=$previousTlsPassword
}

# 保存 PID 跟踪文件
$script:PidsMap | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $PidFile -Encoding UTF8

# ------------------------------------------------------------------ 6. 状态汇总与就绪提示

Write-Host "`n========================================================" -ForegroundColor Green
Write-Host "          所有远程视频银行服务已全部启动就绪！" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green
Write-Host "`n【本机访问地址】" -ForegroundColor Yellow
Write-Host "  PC 坐席端     : https://localhost:5174/" -ForegroundColor Cyan
Write-Host "  渠道演示端     : https://localhost:5175/#/bank" -ForegroundColor Cyan
$lanAddresses = @(Get-LanIPv4)
if ($lanAddresses.Count -gt 0) {
    Write-Host "`n【同一 Wi-Fi 访问地址】" -ForegroundColor Yellow
    foreach ($ip in $lanAddresses) {
        Write-Host "  PC 坐席端     : https://${ip}:5174/" -ForegroundColor Cyan
        Write-Host "  渠道演示端     : https://${ip}:5175/#/bank" -ForegroundColor Cyan
    }
} else {
    Write-Host "`n[提示] 未识别到带默认网关的 Wi-Fi/以太网 IPv4 地址；请检查网络连接后重新运行脚本。" -ForegroundColor Yellow
}
Write-Host "  信令健康检查   : http://localhost:8000/health"
Write-Host "  业务交易目录   : https://localhost:8080/api/platform/trades"
Write-Host "  流程场景接口   : http://localhost:8083/api/platform/scenes"
Write-Host "  核心转账 Mock  : https://localhost:8080/api/mock/core/transfer"
Write-Host "  实时风控 Mock  : https://localhost:8080/api/mock/risk/evaluate"
Write-Host "--------------------------------------------------------" -ForegroundColor DarkGray
Write-Host "运行日志存放目录 : $LogDir"
Write-Host "查看实时状态命令 : .\status.ps1"
Write-Host "停止全部服务命令 : .\stop-all.ps1"
Write-Host "执行全链路验证   : .\verify.ps1"
Write-Host "========================================================`n" -ForegroundColor Green

# 如果指定了 -Verify，直接启动全链路验证
if ($Verify) {
    Start-Sleep -Seconds 2
    & (Join-Path $WorkspaceRoot "verify.ps1")
}

if ($KeepAlive) {
    Write-Host "`n[KeepAlive] 进程保持激活中（服务正在后台监听，按 Ctrl+C 退出）..." -ForegroundColor Cyan
    while ($true) {
        Start-Sleep -Seconds 5
    }
}
