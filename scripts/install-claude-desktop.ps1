param(
    [string]$Version = "1.0.0",
    [string]$Url = "http://127.0.0.1:8765",
    [string]$Token = "",
    [string]$ConfigPath = "$env:APPDATA\Claude\claude_desktop_config.json"
)

$ErrorActionPreference = "Stop"
$cacheDir = Join-Path $env:LOCALAPPDATA "FxPilot"
$jar = Join-Path $cacheDir "fxpilot-$Version.jar"
$downloadUrl = "https://repo1.maven.org/maven2/io/github/dbuedenbender/fxpilot/$Version/fxpilot-$Version.jar"

New-Item -ItemType Directory -Force $cacheDir | Out-Null
if (-not (Test-Path $jar)) {
    Write-Host "[fxpilot] downloading $downloadUrl"
    Invoke-WebRequest -Uri $downloadUrl -OutFile $jar -UseBasicParsing
}

$configDir = Split-Path -Parent $ConfigPath
if ($configDir) {
    New-Item -ItemType Directory -Force $configDir | Out-Null
}

if (Test-Path $ConfigPath) {
    $config = Get-Content -Raw $ConfigPath | ConvertFrom-Json
    Copy-Item $ConfigPath "$ConfigPath.bak" -Force
} else {
    $config = [pscustomobject]@{}
}

if (-not $config.PSObject.Properties["mcpServers"]) {
    $config | Add-Member -NotePropertyName "mcpServers" -NotePropertyValue ([pscustomobject]@{})
}

$envMap = [ordered]@{ FXPILOT_URL = $Url }
if ($Token) {
    $envMap["FXPILOT_TOKEN"] = $Token
}

$entry = [pscustomobject]@{
    command = "java"
    args = @("-jar", $jar)
    env = [pscustomobject]$envMap
}

if ($config.mcpServers.PSObject.Properties["fxpilot"]) {
    $config.mcpServers.fxpilot = $entry
} else {
    $config.mcpServers | Add-Member -NotePropertyName "fxpilot" -NotePropertyValue $entry
}

$json = $config | ConvertTo-Json -Depth 10
[IO.File]::WriteAllText($ConfigPath, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "[fxpilot] jar: $jar"
Write-Host "[fxpilot] config: $ConfigPath"
Write-Host "[fxpilot] restart Claude Desktop completely, then call fx_health"
