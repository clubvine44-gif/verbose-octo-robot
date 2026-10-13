@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "TESSA_BRIDGE_PORT=18766"
set "TESSA_PYTHON="
where py >nul 2>&1
if %ERRORLEVEL% EQU 0 set "TESSA_PYTHON=py -3.12"
if not defined TESSA_PYTHON (
  where python >nul 2>&1
  if %ERRORLEVEL% EQU 0 set "TESSA_PYTHON=python"
)
if not defined TESSA_PYTHON (
  echo ERROR: Python 3.12 not found. Install Python before starting TESSA bridge.
  exit /b 2
)
%TESSA_PYTHON% -c "import aiohttp,mss,PIL,pyautogui" >nul 2>&1
if errorlevel 1 (
  echo TESSA bridge dependencies are not installed.
  echo Install: %TESSA_PYTHON% -m pip install -r "%~dp0requirements.txt"
  exit /b 3
)
echo Starting TESSA mobile bridge (Tailscale + USB-local, TCP 18766)...
echo Authentication key is stored locally in %%LOCALAPPDATA%%\TESSA\mobile-bridge.token
echo No inbound public network port is opened.
%TESSA_PYTHON% -u "%~dp0server.py" --port 18766
echo.
echo TESSA bridge stopped. Exit code %ERRORLEVEL%.
exit /b 1
