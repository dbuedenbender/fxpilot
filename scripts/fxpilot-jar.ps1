# Shared by the installers: downloads the FxPilot jar from Maven Central into the cache and verifies it.

# SHA-256 of the published default version, so the jar is checked against a value from this repository and not
# only against a file from the same server. Keep in sync with fxpilot-mcp.cmd and fxpilot-mcp.sh.
$FxPilotDefaultVersion = "1.1.0"
$FxPilotDefaultSha256 = "dc2825e18b5695d103ec0b49d07a9f1cb9041cccb78966bd9d4170fa9986dfff"

# Returns the path of the cached jar. Another version is checked against $Sha256 or, without it, against the
# .sha256 file Maven Central publishes next to the jar.
function Get-FxPilotJar {
    param(
        [string]$Version = $FxPilotDefaultVersion,
        [string]$Sha256 = ""
    )
    $cacheDir = Join-Path $env:LOCALAPPDATA "FxPilot"
    $jar = Join-Path $cacheDir "fxpilot-$Version.jar"
    if (Test-Path $jar) {
        return $jar
    }
    $url = "https://repo1.maven.org/maven2/io/github/dbuedenbender/fxpilot/$Version/fxpilot-$Version.jar"
    $part = "$jar.part"
    New-Item -ItemType Directory -Force $cacheDir | Out-Null
    Write-Host "[fxpilot] downloading $url"
    try {
        Invoke-WebRequest -Uri $url -OutFile $part -UseBasicParsing
        $expected = $Sha256
        if (-not $expected -and $Version -eq $FxPilotDefaultVersion) {
            $expected = $FxPilotDefaultSha256
        }
        if (-not $expected) {
            Invoke-WebRequest -Uri "$url.sha256" -OutFile "$part.sha256" -UseBasicParsing
            $expected = (Get-Content -Raw "$part.sha256").Trim().Substring(0, 64)
        }
        if ((Get-FileHash -Algorithm SHA256 $part).Hash -ne $expected) {
            throw "checksum mismatch for $url"
        }
        Move-Item -Force $part $jar
    } finally {
        Remove-Item -Force -ErrorAction SilentlyContinue $part, "$part.sha256"
    }
    return $jar
}
