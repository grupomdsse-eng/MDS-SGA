@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0gradle\bootstrap-gradle.ps1" %*
exit /b %ERRORLEVEL%
