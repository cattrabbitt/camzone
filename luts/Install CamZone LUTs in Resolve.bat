@echo off
setlocal
rem Installs the Cam Zone LUTs into DaVinci Resolve's LUT folder, in a "CamZone" subfolder.
set "DEST=%ProgramData%\Blackmagic Design\DaVinci Resolve\Support\LUT\CamZone"

net session >nul 2>&1
if errorlevel 1 (
  echo Asking Windows for permission to write to the Resolve LUT folder...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

if not exist "%DEST%" mkdir "%DEST%"
copy /Y "%~dp0CamZone_*.cube" "%DEST%\" >nul
if errorlevel 1 (
  echo Could not copy the LUTs. Make sure this file sits next to the CamZone .cube files.
) else (
  echo Installed to:
  echo   %DEST%
  echo.
  echo In Resolve: open Project Settings ^> Color Management ^> Lookup Tables and click Update Lists,
  echo or restart Resolve. The LUTs appear under CamZone.
)
echo.
pause
