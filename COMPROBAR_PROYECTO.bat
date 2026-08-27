@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
echo ============================================================
echo           SGA MDS - TEST + COMPILACION DEL APK
echo ============================================================
echo.
call gradlew.bat :app:testDebugUnitTest :app:assembleDebug --stacktrace --no-daemon
if errorlevel 1 (
    echo.
    echo LA COMPILACION HA FALLADO. Revise el error mostrado arriba.
    pause
    exit /b 1
)
echo.
echo CORRECTO.
echo APK: app\build\outputs\apk\debug\app-debug.apk
pause
