# 停止本工作区应用服务，保留共享的 MySQL。
[CmdletBinding(SupportsShouldProcess=$true,ConfirmImpact='Medium')]
param()
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$workspaceRoot=Split-Path -Parent $MyInvocation.MyCommand.Path
$pidFile=Join-Path $workspaceRoot '.service-pids.json'
$targetPorts=@(5175,5174,5173,8090,8080,8081,8082,8083,8084,8085,8000)
$tracked=@{}
$remaining=@{}
$candidates=@{}
$hadFailure=$false
$stopped=[System.Collections.Generic.List[string]]::new()
$allowedNames=@('java','javaw','node','centrifugo','powershell','pwsh')
Write-Host '=== 停止远程视频银行应用服务 ===' -ForegroundColor Cyan

if(Test-Path -LiteralPath $pidFile){
    try{
        $records=Get-Content -LiteralPath $pidFile -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach($entry in $records.PSObject.Properties){
            $record=$entry.Value
            $serviceProcessId=if($record -is [ValueType]){[int]$record}else{[int]$record.pid}
            if($serviceProcessId -gt 0){$tracked[$serviceProcessId]=@{name=$entry.Name;record=$record};$candidates[$serviceProcessId]=$entry.Name}
        }
    }catch{Write-Warning "无法读取进程记录：$($_.Exception.Message)";$hadFailure=$true}
}
# netstat 不依赖 Get-NetTCPConnection 的 CIM 权限。只发现监听者，不据端口直接停进程。
foreach($line in (netstat -ano -p tcp)){
    if($line -match '^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$'){
        $port=[int]$Matches[1];$serviceProcessId=[int]$Matches[2]
        if($targetPorts -contains $port){$candidates[$serviceProcessId]="应用端口 $port"}
    }
}
foreach($serviceProcessId in $candidates.Keys){
    if($serviceProcessId -eq $PID -or $serviceProcessId -le 0){continue}
    $process=Get-Process -Id $serviceProcessId -ErrorAction SilentlyContinue
    if(-not $process){continue}
    $owned=$false
    if($allowedNames -contains $process.ProcessName){
        if($tracked.ContainsKey($serviceProcessId)){
            $record=$tracked[$serviceProcessId].record
            if($record -isnot [ValueType] -and $record.startedAtUtc -and $record.processName){
                try{$owned=$record.processName -eq $process.ProcessName -and ([DateTime]::Parse($record.startedAtUtc).ToUniversalTime() -eq $process.StartTime.ToUniversalTime())}catch{}
            }
        }
        if(-not $owned){
            try{
                $details=Get-CimInstance Win32_Process -Filter "ProcessId=$serviceProcessId" -ErrorAction Stop
                $command=[string]$details.CommandLine
                $owned=$command.IndexOf($workspaceRoot,[StringComparison]::OrdinalIgnoreCase) -ge 0
                if(-not $owned -and $process.ProcessName -eq 'centrifugo'){
                    $alternate=Join-Path (Split-Path $workspaceRoot -Parent) 'flowable-poc\centrifugo\centrifugo.exe'
                    $owned=$command.IndexOf($alternate,[StringComparison]::OrdinalIgnoreCase) -ge 0
                }
            }catch{}
        }
    }
    if(-not $owned){
        Write-Warning "未能确认 PID $serviceProcessId 属于本工作区，未停止。"
        if($tracked.ContainsKey($serviceProcessId)){$remaining[$tracked[$serviceProcessId].name]=$tracked[$serviceProcessId].record}
        $hadFailure=$true
        continue
    }
    if($PSCmdlet.ShouldProcess("$($process.ProcessName) PID $serviceProcessId",'停止工作区服务')){
        $label=if($tracked.ContainsKey($serviceProcessId)){$tracked[$serviceProcessId].name}else{$candidates[$serviceProcessId]}
        try{
            Stop-Process -Id $serviceProcessId -Force -ErrorAction Stop
            $stopped.Add("$label (PID $serviceProcessId)")
            Write-Host "已停止 [$label] (PID $serviceProcessId)" -ForegroundColor Yellow
        }
        catch{Write-Warning $_.Exception.Message;$hadFailure=$true;if($tracked.ContainsKey($serviceProcessId)){$remaining[$tracked[$serviceProcessId].name]=$tracked[$serviceProcessId].record}}
    }
}
if(-not $WhatIfPreference){
    if($remaining.Count -gt 0){$remaining | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $pidFile -Encoding UTF8}
    elseif(Test-Path -LiteralPath $pidFile){if($PSCmdlet.ShouldProcess($pidFile,'清除已结束服务的进程记录')){Remove-Item -LiteralPath $pidFile -Force}}
}
if($WhatIfPreference){Write-Host '预览完成，未停止服务或修改进程记录。';exit 0}
if($hadFailure){Write-Warning '部分进程未停止，请查看上方原因。';exit 1}
Write-Host "`n========================================================" -ForegroundColor Green
Write-Host "本工作区应用服务已停止（$($stopped.Count) 个进程）。" -ForegroundColor Green
Write-Host "MySQL 等共享基础设施未停止。" -ForegroundColor DarkGray
Write-Host "下次启动：.\start-all.ps1 -SkipBuild" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Green
exit 0
