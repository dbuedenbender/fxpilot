#!/usr/bin/env sh
set -eu

# Points the launchers at a published version and pins its SHA-256 in fxpilot-mcp.cmd, fxpilot-mcp.sh and
# fxpilot-jar.ps1. Run it after the deployment is published in the Central Portal: the hash comes from Maven
# Central, and pinning before the artifact exists would make every launcher reject the new version.
#
# Usage: scripts/pin-release.sh <version> [sha256]
#   <version>  published version, e.g. 1.1.1
#   sha256     optional; without it the hash from Maven Central's fxpilot-<version>.jar.sha256 is used
VERSION="${1:-}"
if [ -z "$VERSION" ] || [ "$#" -gt 2 ]; then
    echo "Usage: scripts/pin-release.sh <version> [sha256]" >&2
    exit 2
fi
if ! echo "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "[fxpilot] '$VERSION' is not a version like 1.1.1" >&2
    exit 2
fi

SHA="${2:-}"
if [ -z "$SHA" ]; then
    URL="https://repo1.maven.org/maven2/io/github/dbuedenbender/fxpilot/$VERSION/fxpilot-$VERSION.jar.sha256"
    SUM="$(mktemp)"
    trap 'rm -f "$SUM"' EXIT
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$URL" -o "$SUM" || fetched=1
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$SUM" "$URL" || fetched=1
    else
        echo "[fxpilot] curl or wget is required, or pass the SHA-256 as the second argument" >&2
        exit 1
    fi
    if [ "${fetched:-0}" = 1 ]; then
        echo "[fxpilot] could not download $URL. Is $VERSION published on Maven Central? Pass the SHA-256 as" >&2
        echo "[fxpilot] the second argument to skip the download." >&2
        exit 1
    fi
    SHA="$(cut -c1-64 "$SUM")"
fi
SHA="$(echo "$SHA" | tr 'A-F' 'a-f')"
if ! echo "$SHA" | grep -Eq '^[0-9a-f]{64}$'; then
    echo "[fxpilot] '$SHA' is not a SHA-256. Is $VERSION published on Maven Central?" >&2
    exit 1
fi

cd "$(dirname "$0")"

replace() { # file sed-pattern replacement
    if ! grep -Eq "$2" "$1"; then
        echo "[fxpilot] no line matching '$2' in $1" >&2
        exit 1
    fi
    sed "s|$2|$3|" "$1" > "$1.pin-release.tmp"
    mv "$1.pin-release.tmp" "$1"
}

replace fxpilot-mcp.cmd '^set "DEFAULT_VERSION=[^"]*"' 'set "DEFAULT_VERSION='"$VERSION"'"'
replace fxpilot-mcp.cmd '^set "DEFAULT_SHA256=[^"]*"' 'set "DEFAULT_SHA256='"$SHA"'"'
replace fxpilot-mcp.sh '^DEFAULT_VERSION="[^"]*"' 'DEFAULT_VERSION="'"$VERSION"'"'
replace fxpilot-mcp.sh '^DEFAULT_SHA256="[^"]*"' 'DEFAULT_SHA256="'"$SHA"'"'
replace fxpilot-jar.ps1 '^\$FxPilotDefaultVersion = "[^"]*"' '$FxPilotDefaultVersion = "'"$VERSION"'"'
replace fxpilot-jar.ps1 '^\$FxPilotDefaultSha256 = "[^"]*"' '$FxPilotDefaultSha256 = "'"$SHA"'"'

echo "[fxpilot] launchers now pin $VERSION ($SHA)"
echo "[fxpilot] LauncherScriptsTest checks that the three scripts agree; commit the change."
