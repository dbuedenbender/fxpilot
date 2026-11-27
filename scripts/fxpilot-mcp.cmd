@echo off
setlocal
rem SHA-256 of the published default version, so the jar is checked against a value from this repository and not
rem only against a file from the same server. Another FXPILOT_VERSION is checked against FXPILOT_SHA256 or, without
rem it, against the .sha256 file Maven Central publishes next to the jar.
set "DEFAULT_VERSION=1.1.1"
set "DEFAULT_SHA256=a74747a020d1e0be968e453943da7a3353a7d261f444f79bcdcdbe628598193d"

if "%FXPILOT_VERSION%"=="" set "FXPILOT_VERSION=%DEFAULT_VERSION%"
set "CACHE_DIR=%LOCALAPPDATA%\FxPilot"
set "JAR=%CACHE_DIR%\fxpilot-%FXPILOT_VERSION%.jar"
set "FXP_URL=https://repo1.maven.org/maven2/io/github/dbuedenbender/fxpilot/%FXPILOT_VERSION%/fxpilot-%FXPILOT_VERSION%.jar"
set "FXP_PART=%JAR%.part"
set "FXP_EXPECTED=%FXPILOT_SHA256%"
if "%FXP_EXPECTED%"=="" if "%FXPILOT_VERSION%"=="%DEFAULT_VERSION%" set "FXP_EXPECTED=%DEFAULT_SHA256%"

if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"
if not exist "%JAR%" (
    echo [fxpilot] downloading %FXP_URL% 1>&2
    rem PowerShell instead of curl.exe: curl's schannel fails where the certificate revocation server is unreachable.
    rem Everything goes to stderr, because stdout belongs to the MCP protocol.
    powershell.exe -NoProfile -NonInteractive -Command "$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue'; try { Invoke-WebRequest -UseBasicParsing -Uri $env:FXP_URL -OutFile $env:FXP_PART; $want = $env:FXP_EXPECTED; if (-not $want) { Invoke-WebRequest -UseBasicParsing -Uri ($env:FXP_URL + '.sha256') -OutFile ($env:FXP_PART + '.sha256'); $want = (Get-Content -Raw ($env:FXP_PART + '.sha256')).Trim().Substring(0, 64); Remove-Item ($env:FXP_PART + '.sha256') }; if ((Get-FileHash -Algorithm SHA256 $env:FXP_PART).Hash -ne $want) { throw ('checksum mismatch for ' + $env:FXP_URL) }; Move-Item -Force $env:FXP_PART $env:JAR } catch { [Console]::Error.WriteLine('[fxpilot] download failed: ' + $_.Exception.Message); Remove-Item -Force -ErrorAction SilentlyContinue $env:FXP_PART, ($env:FXP_PART + '.sha256'); exit 1 }" 1>&2
    if errorlevel 1 exit /b 1
)

java -jar "%JAR%"
