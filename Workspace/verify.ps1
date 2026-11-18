# 全服务自动化联调与功能验证脚本
$ErrorActionPreference = "Continue"

$WorkspaceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $WorkspaceRoot

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Invoke-ApiTest {
    param(
        [string]$Name,
        [string]$Method,
        [string]$Url,
        [string]$Body = $null,
        [scriptblock]$Validator = $null,
        [int]$TimeoutSec = 8
    )

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $success = $false
    $details = ""
    try {
        $headers = @{ "Content-Type" = "application/json" }
        $params = @{
            Uri = $Url
            Method = $Method
            TimeoutSec = $TimeoutSec
            UseBasicParsing = $true
        }
        if ($PSVersionTable.PSVersion.Major -ge 6) {
            $params["SkipCertificateCheck"] = $true
        }
        if ($Body) {
            $params["Headers"] = $headers
            $params["Body"] = [System.Text.Encoding]::UTF8.GetBytes($Body)
        }

        $res = Invoke-WebRequest @params
        $sw.Stop()
        $statusCode = [int]$res.StatusCode
        $content = if ($res.Content -is [byte[]]) {
            [System.Text.Encoding]::UTF8.GetString($res.Content)
        } else {
            [string]$res.Content
        }

        if ($Validator) {
            $valResult = & $Validator $statusCode $content
            if ($valResult.Pass) {
                $success = $true
                $details = $valResult.Msg
            } else {
                $success = $false
                $details = $valResult.Msg
            }
        } else {
            if ($statusCode -ge 200 -and $statusCode -lt 300) {
                $success = $true
                $details = "HTTP $statusCode (${sw.ElapsedMilliseconds}ms)"
            } else {
                $success = $false
                $details = "HTTP $statusCode"
            }
        }
    } catch {
        $sw.Stop()
        $errMsg = $_.Exception.Message
        if ($_.Exception.Response) {
            $code = [int]$_.Exception.Response.StatusCode
            $details = "HTTP ${code}: $errMsg"
        } else {
            $details = "连接失败: $errMsg"
        }
    }

    $color = if ($success) { "Green" } else { "Red" }
    $tag = if ($success) { "[PASS]" } else { "[FAIL]" }
    Write-Host ("{0,-6} " -f $tag) -ForegroundColor $color -NoNewline
    Write-Host ("{0,-38} {1,-6} {2}" -f $Name, $Method, $details)
    return $success
}

Write-Host "`n========================================================" -ForegroundColor Cyan
Write-Host "         远程视频银行 · 全服务链条功能验证" -ForegroundColor Cyan
Write-Host "========================================================`n" -ForegroundColor Cyan

$passedCount = 0
$totalCount = 0

function Run-Test {
    param([string]$Name, [string]$Method, [string]$Url, [string]$Body = $null, [scriptblock]$Validator = $null, [int]$TimeoutSec = 8)
    $script:totalCount++
    if (Invoke-ApiTest -Name $Name -Method $Method -Url $Url -Body $Body -Validator $Validator -TimeoutSec $TimeoutSec) {
        $script:passedCount++
    }
}

# 1. Centrifugo 信令引擎
Run-Test -Name "Centrifugo 信令健康检查" -Method "GET" -Url "http://localhost:8000/health" -Validator {
    param($code, $body)
    return @{ Pass = ($code -eq 200); Msg = "Centrifugo 运行正常 (HTTP 200)" }
}

# 2. workflow-svc (8083) 流程启动与实例准备
$testProcessId = $null
try {
    $startParams = @{
        Uri = "http://localhost:8083/internal/process/start"
        Method = "POST"
        Headers = @{ "Content-Type" = "application/json" }
        Body = '{"processDefinitionKey":"balance_sop"}'
        TimeoutSec = 5
        UseBasicParsing = $true
    }
    $startRes = Invoke-WebRequest @startParams
    $startJson = $startRes.Content | ConvertFrom-Json
    $testProcessId = $startJson.processId
} catch {}

# 3. media-svc (8084) 音视频房间分配（/room/{id}/open 要求 JSON 请求体）
$roomUrl = if ($testProcessId) { "http://localhost:8084/internal/room/$testProcessId/open" } else { "http://localhost:8084/internal/room/test-verify-p1/open" }
Run-Test -Name "media-svc 音视频房间分配" -Method "POST" -Url $roomUrl -Body '{"customer_id":"VERIFY-CUSTOMER","agent_session_id":"VERIFY-AGENT"}' -Validator {
    param($code, $body)
    $json = $body | ConvertFrom-Json
    return @{ Pass = ($code -eq 200 -and $json.roomId -ne $null); Msg = "分配成功 roomId=$($json.roomId)" }
}

# 4. signaling-svc (8082) 信令发布
Run-Test -Name "signaling-svc 坐席信令发布" -Method "POST" -Url "http://localhost:8082/internal/agent/AGENT-9527/publish" `
    -Body '{"type":"PING","payload":{"hello":"world"}}' -Validator {
    param($code, $body)
    return @{ Pass = ($code -eq 200); Msg = "信令下发成功" }
}

# 5. routing-svc (8081) 坐席排队
Run-Test -Name "routing-svc 队列列表查询" -Method "GET" -Url "http://localhost:8081/internal/queue" -Validator {
    param($code, $body)
    return @{ Pass = ($code -eq 200); Msg = "查询成功" }
}

# 6. workflow-svc (8083) 流程定义部署（/internal/health 报告已部署的流程定义数）
Run-Test -Name "workflow-svc 流程定义已部署" -Method "GET" -Url "http://localhost:8083/internal/health" -Validator {
    param($code, $body)
    $json = $body | ConvertFrom-Json
    return @{ Pass = ($code -eq 200 -and $json.processDefinitions -ge 1); Msg = "流程定义=$($json.processDefinitions) 运行实例=$($json.runningInstances)" }
}

# 6. business-svc (8080) 业务核心与 Mock 接口面
Run-Test -Name "business-svc 可办交易目录" -Method "GET" -Url "https://localhost:8080/api/platform/trades" -Validator {
    param($code, $body)
    $json = $body | ConvertFrom-Json
    return @{ Pass = ($code -eq 200 -and $json.success); Msg = "获取交易品种成功" }
}

Run-Test -Name "business-svc 客户档案画像评级" -Method "POST" -Url "https://localhost:8080/api/customer/identify" `
    -Body '{"certNo":"110101199003072391","assetTier":1}' -Validator {
    param($code, $body)
    $json = $body | ConvertFrom-Json
    return @{ Pass = ($code -eq 200 -and $json.level -eq "PRIVATE"); Msg = "识别成功: 等级=$($json.levelName)" }
}

Run-Test -Name "Mock: 天策实时风控小额直通" -Method "POST" -Url "https://localhost:8080/api/mock/risk/evaluate" `
    -Body '{"amount":10000.0,"payee":"李四"}' -Validator {
    param($code, $body)
    $json = $body | ConvertFrom-Json
    return @{ Pass = ($json.data.needHuman -eq $false); Msg = "决策: $($json.data.riskLevel) 评分=$($json.data.riskScore) 直通核心" }
}

Run-Test -Name "Mock: 天策实时风控大额转人工" -Method "POST" -Url "https://localhost:8080/api/mock/risk/evaluate" `
    -Body '{"amount":600000.0,"payee":"王总"}' -Validator {
    param($code, $body)
    $json = $body | ConvertFrom-Json
    return @{ Pass = ($json.data.needHuman -eq $true); Msg = "决策: $($json.data.riskLevel) 评分=$($json.data.riskScore) 阻断并转人工" }
}

Run-Test -Name "Mock: 核心系统转账扣款记账" -Method "POST" -Url "https://localhost:8080/api/mock/core/transfer" `
    -Body '{"bizNo":"VERIFY-001","amount":50000.0,"payeeName":"张三"}' -Validator {
    param($code, $body)
    $json = $body | ConvertFrom-Json
    return @{ Pass = ($json.data.status -eq "SUCCESS"); Msg = "记账成功: 流水号=$($json.data.coreTxId)" }
}

Run-Test -Name "Mock: 核心系统账户余额查询" -Method "POST" -Url "https://localhost:8080/api/mock/core/balance/query" `
    -Body '{"accountNo":"6222021000123456"}' -Validator {
    param($code, $body)
    $json = $body | ConvertFrom-Json
    return @{ Pass = ($json.data.availableBalance -gt 0); Msg = "查询成功: 余额=$($json.data.formattedBalance)" }
}

Run-Test -Name "Mock: 金融短信通知专用通道" -Method "POST" -Url "https://localhost:8080/api/mock/notify/sms/send" `
    -Body '{"mobile":"13800138000","amount":5000.0,"payee":"赵六"}' -Validator {
    param($code, $body)
    $json = $body | ConvertFrom-Json
    return @{ Pass = ($json.data.deliverStatus -eq "DELIVRD"); Msg = "送达: $($json.data.content)" }
}

Run-Test -Name "Mock: 公安人脸活体三要素比对" -Method "POST" -Url "https://localhost:8080/api/mock/id/face-verify" `
    -Body '{"certNo":"110101199003072391","customerName":"张三"}' -Validator {
    param($code, $body)
    $json = $body | ConvertFrom-Json
    return @{ Pass = ($json.data.pass -eq $true); Msg = "核验通过: 相似度=$($json.data.similarity)% 活体得分=$($json.data.livenessScore)" }
}

# 请求体携带 agentId 会触发坐席会话鉴权（AgentIdentityAdvice）；缺省时 mock 使用默认坐席
Run-Test -Name "Mock: CFCA国密SM2电子签章" -Method "POST" -Url "https://localhost:8080/api/mock/sign/contract" `
    -Body '{"bizNo":"VERIFY-001","customerName":"张三"}' -Validator {
    param($code, $body)
    $json = $body | ConvertFrom-Json
    return @{ Pass = ($json.data.signStatus -eq "SUCCESS"); Msg = "签署成功: CA序列号=$($json.data.caCertSerialNo)" }
}

Run-Test -Name "Mock: 银行卡Bin与联行号核验" -Method "POST" -Url "https://localhost:8080/api/mock/account/verify" `
    -Body '{"accountNo":"6222021000123456"}' -Validator {
    param($code, $body)
    $json = $body | ConvertFrom-Json
    return @{ Pass = ($json.data.valid -eq $true); Msg = "核验通过: $($json.data.bankName) ($($json.data.cardTypeName)) 联行号=$($json.data.interbankClearingCode)" }
}

# 7. digital-human (MiMo 2.5) 数字人与大模型交互
Run-Test -Name "DigitalHuman 数字人配置" -Method "GET" -Url "https://localhost:8080/api/balance/ai/config" -Validator {
    param($code, $body)
    $json = $body | ConvertFrom-Json
    $model = $json.data.llm.model
    return @{ Pass = ($code -eq 200 -and -not [string]::IsNullOrEmpty($model)); Msg = "配置正常: 默认模型=$model 供应商=$($json.data.llm.provider)" }
}

# 意图对话依赖上游大模型密钥（LLM_API_KEY）；上游未授权/不可达属环境缺失，按 SKIP 处理
$script:totalCount++
try {
    $chatParams = @{
        Uri = "https://localhost:8080/api/balance/ai/chat"
        Method = "POST"
        Headers = @{ "Content-Type" = "application/json" }
        Body = [System.Text.Encoding]::UTF8.GetBytes('{"text":"我要给王总转账200万"}')
        TimeoutSec = 20
        UseBasicParsing = $true
    }
    if ($PSVersionTable.PSVersion.Major -ge 6) { $chatParams["SkipCertificateCheck"] = $true }
    $chatRes = Invoke-WebRequest @chatParams
    $chatJson = $chatRes.Content | ConvertFrom-Json
    if ($chatJson.success -eq $true -and $chatJson.intent -eq "TRANSFER" -and $chatJson.amount -eq 2000000) {
        $script:passedCount++
        Write-Host ("{0,-6} " -f "[PASS]") -ForegroundColor Green -NoNewline
        Write-Host ("{0,-38} {1,-6} {2}" -f "DigitalHuman 转账意图对话", "POST", "识别成功: 意图=$($chatJson.intent), 金额=¥$($chatJson.amount), 收款人=$($chatJson.payee)")
    } else {
        Write-Host ("{0,-6} " -f "[FAIL]") -ForegroundColor Red -NoNewline
        Write-Host ("{0,-38} {1,-6} {2}" -f "DigitalHuman 转账意图对话", "POST", "返回未命中转账意图: $($chatRes.Content)")
    }
} catch {
    $code = 0
    try { $code = [int]$_.Exception.Response.StatusCode } catch {}
    if ($code -eq 401 -or $code -eq 502 -or $code -eq 503) {
        $script:totalCount--
        Write-Host ("{0,-6} " -f "[SKIP]") -ForegroundColor DarkYellow -NoNewline
        Write-Host ("{0,-38} {1,-6} {2}" -f "DigitalHuman 转账意图对话", "POST", "大模型上游未授权/不可达 (HTTP $code)，需配置有效 LLM_API_KEY")
    } else {
        Write-Host ("{0,-6} " -f "[FAIL]") -ForegroundColor Red -NoNewline
        Write-Host ("{0,-38} {1,-6} {2}" -f "DigitalHuman 转账意图对话", "POST", "HTTP $code $($_.Exception.Message)")
    }
}

# 8. Vue3 前端与反代（agent-web:5174 坐席端 / enterprise-demo:5175 渠道端，均为 HTTPS+Vite 反代）
Run-Test -Name "agent-web 坐席工作台页面" -Method "GET" -Url "https://localhost:5174/" -Validator {
    param($code, $body)
    return @{ Pass = ($code -eq 200 -and $body.Contains('id="app"')); Msg = "页面加载正常 (200 OK)" }
}

Run-Test -Name "enterprise-demo 渠道演示页面" -Method "GET" -Url "https://localhost:5175/" -Validator {
    param($code, $body)
    return @{ Pass = ($code -eq 200 -and $body.Contains('id="app"')); Msg = "页面加载正常 (200 OK)" }
}

Run-Test -Name "agent-web 业务反向代理(/api/**)" -Method "GET" -Url "https://localhost:5174/api/platform/trades" -Validator {
    param($code, $body)
    $json = $body | ConvertFrom-Json
    return @{ Pass = ($code -eq 200 -and $json.success); Msg = "Vite 反代至 business-svc HTTPS 正常" }
}

Write-Host "`n========================================================" -ForegroundColor Cyan
if ($passedCount -eq $totalCount) {
    Write-Host "  验证全部通过！[$passedCount/$totalCount] 所有服务与Mock接口工作正常" -ForegroundColor Green
} else {
    Write-Host "  部分验证未通过：[$passedCount/$totalCount]，请检查异常服务日志。" -ForegroundColor Yellow
}
Write-Host "========================================================`n" -ForegroundColor Cyan
