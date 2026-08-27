@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

echo ============================================================
echo             SGA MDS - SUBIR PROYECTO A GITHUB
echo ============================================================
echo.

where git >nul 2>nul
if errorlevel 1 (
    echo ERROR: Git no esta instalado o no esta en el PATH.
    echo Instala Git for Windows o utiliza GitHub Desktop.
    echo.
    pause
    exit /b 1
)

set /p REPO_URL=Pegue la URL HTTPS del repositorio vacio ^(ej. https://github.com/usuario/sga-mds.git^): 
if "%REPO_URL%"=="" (
    echo No se ha indicado ningun repositorio.
    pause
    exit /b 1
)

if not exist ".git" (
    git init
    if errorlevel 1 goto :error
)

git branch -M main

git add -A
if errorlevel 1 goto :error

git diff --cached --quiet
if errorlevel 1 (
    git commit -m "SGA MDS - proyecto Android inicial"
    if errorlevel 1 goto :error
) else (
    echo No hay cambios nuevos que guardar.
)

git remote get-url origin >nul 2>nul
if errorlevel 1 (
    git remote add origin "%REPO_URL%"
) else (
    git remote set-url origin "%REPO_URL%"
)
if errorlevel 1 goto :error

echo.
echo Subiendo proyecto completo a GitHub...
git push -u origin main
if errorlevel 1 goto :error

echo.
echo ============================================================
echo CORRECTO: proyecto subido conservando todas las carpetas.
echo Ahora entra en GitHub - Actions - Build Android APK.
echo ============================================================
pause
exit /b 0

:error
echo.
echo ERROR: Git ha devuelto un error. Revise el mensaje anterior.
echo Si GitHub solicita autenticacion, complete el inicio de sesion.
pause
exit /b 1
