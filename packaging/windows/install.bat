@echo off
setlocal enabledelayedexpansion

echo ============================================================
echo          GolosAI Voice Assistant - Windows Installer       
echo ============================================================
echo.

set "INSTALL_DIR=%LOCALAPPDATA%\Programs\GolosAI"
set "BIN_DIR=%INSTALL_DIR%\bin"
set "START_MENU=%APPDATA%\Microsoft\Windows\Start Menu\Programs\GolosAI"
set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%..\.."

REM 1. Check for Java 21+
echo [*] Checking for Java runtime...
java -version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [!] Java is not found in PATH.
    echo     Attempting to download and install Microsoft OpenJDK 21 via winget...
    winget install Microsoft.OpenJDK.21 --accept-package-agreements --accept-source-agreements
    if %ERRORLEVEL% NEQ 0 (
        echo [!] Could not install automatically via winget. Please download and install OpenJDK 21 from:
        echo     https://adoptium.net/temurin/releases/?version=21
        pause
        exit /b 1
    )
)

REM 2. Create destination directories
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
if not exist "%START_MENU%" mkdir "%START_MENU%"

REM 3. Build or copy binaries
if exist "%PROJECT_ROOT%\gradlew.bat" (
    echo [*] Building application binaries...
    pushd "%PROJECT_ROOT%"
    call gradlew.bat :application:installDist --no-daemon -q
    popd
    xcopy /E /I /Y "%PROJECT_ROOT%\application\build\install\application\*" "%INSTALL_DIR%\"
) else (
    xcopy /E /I /Y "%SCRIPT_DIR%application\*" "%INSTALL_DIR%\"
)

REM Copy bundled models if available
if exist "%PROJECT_ROOT%\models" (
    if not exist "%INSTALL_DIR%\models" mkdir "%INSTALL_DIR%\models"
    xcopy /E /I /Y "%PROJECT_ROOT%\models\*" "%INSTALL_DIR%\models\"
)

REM 4. Create Desktop & Start Menu Shortcut via PowerShell
echo [*] Creating Desktop and Start Menu shortcuts...
set "DESKTOP_PATH=%USERPROFILE%\Desktop"
set "TARGET_EXE=%BIN_DIR%\application.bat"

powershell -NoProfile -ExecutionPolicy Bypass -Command "$WshShell = New-Object -ComObject WScript.Shell; $Shortcut = $WshShell.CreateShortcut('%DESKTOP_PATH%\GolosAI.lnk'); $Shortcut.TargetPath = '%TARGET_EXE%'; $Shortcut.WorkingDirectory = '%INSTALL_DIR%'; $Shortcut.Description = 'GolosAI Local Voice Assistant'; $Shortcut.WindowStyle = 7; $Shortcut.Save()"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$WshShell = New-Object -ComObject WScript.Shell; $Shortcut = $WshShell.CreateShortcut('%START_MENU%\GolosAI.lnk'); $Shortcut.TargetPath = '%TARGET_EXE%'; $Shortcut.WorkingDirectory = '%INSTALL_DIR%'; $Shortcut.Description = 'GolosAI Local Voice Assistant'; $Shortcut.WindowStyle = 7; $Shortcut.Save()"

echo.
echo ============================================================
echo          GolosAI successfully installed on Windows!         
echo ============================================================
echo.
echo You can launch GolosAI from:
echo   - Desktop shortcut: 'GolosAI'
echo   - Start Menu: 'GolosAI'
echo.
echo Default push-to-talk hotkey is F8 (configurable in Settings).
echo ============================================================
pause
