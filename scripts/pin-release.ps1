# Points the launchers at a published version and pins its SHA-256 in fxpilot-mcp.cmd, fxpilot-mcp.sh and
# fxpilot-jar.ps1. Run it after the deployment is published in the Central Portal: the hash comes from Maven
# Central, and pinning before the artifact exists would make every launcher reject the new version.
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Sha256 = ""
)

$ErrorActionPreference = "Stop"

# Replace expects a literal replacement; the match is passed to a script block so '$' in the PS1 file stays as is.
function Update-File([string]$Name, [string]$Pattern, [string]$Replacement) {
    $path = Join-Path $PSScriptRoot $Name
    $text = [System.IO.File]::ReadAllText($path)
    if ([regex]::Matches($text, $Pattern).Count -eq 0) {
        throw "no line matching '$Pattern' in $Name"
    }
    $updated = [regex]::Replace($text, $Pattern, { param($match) $Replacement })
    if ($updated -ne $text) {
        [System.IO.File]::WriteAllText($path, $updated, (New-Object System.Text.UTF8Encoding($false)))
    }
}

try {
    if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') {
        throw "'$Version' is not a version like 1.1.1"
    }

    if (-not $Sha256) {
        $url = "https://repo1.maven.org/maven2/io/github/dbuedenbender/fxpilot/$Version/fxpilot-$Version.jar.sha256"
        try {
            $text = (Invoke-WebRequest -Uri $url -UseBasicParsing).Content
        } catch {
            throw "could not download $url. Is $Version published on Maven Central? Pass -Sha256 to skip the download."
        }
        if ($text -is [byte[]]) {
            $text = [System.Text.Encoding]::UTF8.GetString($text)
        }
        $Sha256 = ($text.Trim().Substring(0, 64))
    }
    $Sha256 = $Sha256.ToLowerInvariant()
    if ($Sha256 -notmatch '^[0-9a-f]{64}$') {
        throw "'$Sha256' is not a SHA-256. Is $Version published on Maven Central?"
    }

    Update-File "fxpilot-mcp.cmd" '(?m)^set "DEFAULT_VERSION=[^"]*"' "set `"DEFAULT_VERSION=$Version`""
    Update-File "fxpilot-mcp.cmd" '(?m)^set "DEFAULT_SHA256=[^"]*"' "set `"DEFAULT_SHA256=$Sha256`""
    Update-File "fxpilot-mcp.sh" '(?m)^DEFAULT_VERSION="[^"]*"' "DEFAULT_VERSION=`"$Version`""
    Update-File "fxpilot-mcp.sh" '(?m)^DEFAULT_SHA256="[^"]*"' "DEFAULT_SHA256=`"$Sha256`""
    Update-File "fxpilot-jar.ps1" '(?m)^\$FxPilotDefaultVersion = "[^"]*"' "`$FxPilotDefaultVersion = `"$Version`""
    Update-File "fxpilot-jar.ps1" '(?m)^\$FxPilotDefaultSha256 = "[^"]*"' "`$FxPilotDefaultSha256 = `"$Sha256`""
} catch {
    [Console]::Error.WriteLine("[fxpilot] " + $_.Exception.Message)
    exit 1
}

Write-Host "[fxpilot] launchers now pin $Version ($Sha256)"
Write-Host "[fxpilot] LauncherScriptsTest checks that the three scripts agree; commit the change."
