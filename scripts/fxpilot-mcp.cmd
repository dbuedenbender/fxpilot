@echo off
setlocal
if "%FXPILOT_VERSION%"=="" set "FXPILOT_VERSION=1.1.0"
set "CACHE_DIR=%LOCALAPPDATA%\FxPilot"
set "JAR=%CACHE_DIR%\fxpilot-%FXPILOT_VERSION%.jar"
set "URL=https://repo1.maven.org/maven2/io/github/dbuedenbender/fxpilot/%FXPILOT_VERSION%/fxpilot-%FXPILOT_VERSION%.jar"

if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"
if not exist "%JAR%" (
    echo [fxpilot] downloading %URL% 1>&2
    curl.exe -fL "%URL%" -o "%JAR%"
    if errorlevel 1 (
        if exist "%JAR%" del "%JAR%"
        echo [fxpilot] download failed 1>&2
        exit /b 1
    )
)

java -jar "%JAR%"
