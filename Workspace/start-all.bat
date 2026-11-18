@echo off
setlocal EnableExtensions
cd /d "%~dp0"
if /i "%~1"=="--help" goto help
if /i "%~1"=="/?" goto help
if not exist "%~dp0start-all.ps1" (
    echo ERROR: start-all.ps1 was not found.
    pause
    exit /b 1
)
if "%~1"=="" (
    powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" -SkipBuild
) else if /i "%~1"=="--build" (
    powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all.ps1"
) else (
    powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" %*
)
set "result=%errorlevel%"
if not "%result%"=="0" echo Startup failed. See the output above and the logs folder.
if "%result%"=="0" echo Services are running in the background. The PowerShell summary above includes local and same-Wi-Fi access URLs.
pause
exit /b %result%
:help
echo Usage:
echo   start-all.bat                      Start using existing build artifacts.
echo   start-all.bat --build              Build and start; stop running services first.
echo   start-all.bat -SkipBuild -Verify   Start and run integration verification.
echo Requires Java, Maven, Node.js on PATH and an available MySQL database.
exit /b 0
