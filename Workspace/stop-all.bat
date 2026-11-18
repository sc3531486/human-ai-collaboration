@echo off
setlocal EnableExtensions
cd /d "%~dp0"
if /i "%~1"=="--help" goto help
if /i "%~1"=="/?" goto help
if not exist "%~dp0stop-all.ps1" (
    echo ERROR: stop-all.ps1 was not found.
    pause
    exit /b 1
)
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-all.ps1" %*
set "result=%errorlevel%"
if not "%result%"=="0" echo Some services could not be stopped. See the output above.
if "%result%"=="0" echo All workspace services have stopped. Shared MySQL was left running.
pause
exit /b %result%
:help
echo Usage:
echo   stop-all.bat           Stop this workspace's application services.
echo   stop-all.bat -WhatIf   Preview without stopping services or changing PID records.
echo The shared MySQL Windows service is not stopped.
exit /b 0
