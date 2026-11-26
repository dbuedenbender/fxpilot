param(
    [string]$Version = "",
    [string]$Sha256 = "",
    [string]$Url = "http://127.0.0.1:8765",
    [string]$Token = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "fxpilot-jar.ps1")
if (-not $Version) {
    $Version = $FxPilotDefaultVersion
}

if (-not (Get-Command claude -ErrorAction SilentlyContinue)) {
    throw "Claude Code CLI ('claude') is not installed or not on PATH."
}

$jar = Get-FxPilotJar -Version $Version -Sha256 $Sha256

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
