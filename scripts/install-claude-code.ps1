param(
    [string]$Version = "1.0.0",
    [string]$Url = "http://127.0.0.1:8765",
    [string]$Token = ""
)

$ErrorActionPreference = "Stop"
$cacheDir = Join-Path $env:LOCALAPPDATA "FxPilot"
$jar = Join-Path $cacheDir "fxpilot-$Version.jar"
$downloadUrl = "https://repo1.maven.org/maven2/io/github/dbuedenbender/fxpilot/$Version/fxpilot-$Version.jar"

if (-not (Get-Command claude -ErrorAction SilentlyContinue)) {
    throw "Claude Code CLI ('claude') is not installed or not on PATH."
}

New-Item -ItemType Directory -Force $cacheDir | Out-Null
if (-not (Test-Path $jar)) {
    Write-Host "[fxpilot] downloading $downloadUrl"
    Invoke-WebRequest -Uri $downloadUrl -OutFile $jar -UseBasicParsing
}

$remove = @("mcp", "remove", "--scope", "user", "fxpilot")
& claude @remove 2>$null | Out-Null

$argsList = @("mcp", "add", "--scope", "user", "fxpilot", "-e", "FXPILOT_URL=$Url")
if ($Token) {
    $argsList += @("-e", "FXPILOT_TOKEN=$Token")
}
$argsList += @("--", "java", "-jar", $jar)

& claude @argsList
if ($LASTEXITCODE -ne 0) {
    throw "claude mcp add failed with exit code $LASTEXITCODE"
}

Write-Host "[fxpilot] jar: $jar"
Write-Host "[fxpilot] registered for Claude Code; run 'claude mcp list' to verify"
