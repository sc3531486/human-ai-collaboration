# 查看远程视频银行各服务运行状态
$ErrorActionPreference = "SilentlyContinue"

$WorkspaceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $WorkspaceRoot

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

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Services = @(
    @{ Name = "Centrifugo 信令引擎"; Port = 8000; HealthUrl = "http://localhost:8000/health" },
    @{ Name = "media-svc 音视频房间"; Port = 8084; HealthUrl = "http://localhost:8084/internal/health" },
    @{ Name = "signaling-svc 信令转发"; Port = 8082; HealthUrl = "http://localhost:8082/internal/health" },
    @{ Name = "routing-svc 坐席排队路由"; Port = 8081; HealthUrl = "http://localhost:8081/internal/queue" },
    @{ Name = "workflow-svc 流程编排引擎"; Port = 8083; HealthUrl = "http://localhost:8083/internal/health" },
    @{ Name = "business-svc 业务门面中枢"; Port = 8080; HealthUrl = "https://localhost:8080/api/platform/trades" },
    @{ Name = "agent-web Vue3 坐席端"; Port = 5174; HealthUrl = "https://localhost:5174/" },
    @{ Name = "enterprise-demo Vue3 渠道端"; Port = 5175; HealthUrl = "https://localhost:5175/" }
)

Write-Host "`n=== 远程视频银行 · 服务状态检查 ===" -ForegroundColor Cyan
Write-Host ("{0,-26} {1,-8} {2,-10} {3,-10} {4}" -f "服务名称", "端口", "TCP状态", "PID", "健康响应") -ForegroundColor DarkGray
Write-Host ("-" * 72) -ForegroundColor DarkGray

[Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }

foreach ($svc in $Services) {
    $port = $svc.Port
    $name = $svc.Name
    $isListening = Test-Port -Port $port
    
    $pidStr = "-"
    if ($isListening) {
        $conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($conn) { $pidStr = $conn.OwningProcess }
    }

    $statusStr = if ($isListening) { "RUNNING" } else { "STOPPED" }
    $statusColor = if ($isListening) { "Green" } else { "Red" }

    $healthStr = "-"
    if ($isListening -and $svc.HealthUrl) {
        try {
            $params = @{
                Uri = $svc.HealthUrl
                Method = "GET"
                TimeoutSec = 5
                UseBasicParsing = $true
            }
            if ($PSVersionTable.PSVersion.Major -ge 6) {
                $params["SkipCertificateCheck"] = $true
            }
            $res = Invoke-WebRequest @params
            $healthStr = "HTTP $([int]$res.StatusCode)"
        } catch {
            if ($_.Exception.Response) {
                $code = [int]$_.Exception.Response.StatusCode
                $healthStr = "HTTP $code"
            } else {
                $healthStr = "ERR"
            }
        }
    }

    Write-Host ("{0,-24} {1,-8} " -f $name, $port) -NoNewline
    Write-Host ("{0,-10} " -f $statusStr) -ForegroundColor $statusColor -NoNewline
    Write-Host ("{0,-10} {1}" -f $pidStr, $healthStr)
}
Write-Host ""
