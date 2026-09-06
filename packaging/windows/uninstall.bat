@echo off
setlocal

echo ============================================================
echo          GolosAI Voice Assistant - Windows Uninstaller     
echo ============================================================
echo.

set "INSTALL_DIR=%LOCALAPPDATA%\Programs\GolosAI"
set "START_MENU=%APPDATA%\Microsoft\Windows\Start Menu\Programs\GolosAI"
set "DESKTOP_SHORTCUT=%USERPROFILE%\Desktop\GolosAI.lnk"
set "STARTUP_FILE=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\golos-ai.bat"

if exist "%INSTALL_DIR%" rmdir /S /Q "%INSTALL_DIR%"
if exist "%START_MENU%" rmdir /S /Q "%START_MENU%"
if exist "%DESKTOP_SHORTCUT%" del /F /Q "%DESKTOP_SHORTCUT%"
if exist "%STARTUP_FILE%" del /F /Q "%STARTUP_FILE%"

echo [OK] GolosAI and all shortcuts removed successfully.
pause
