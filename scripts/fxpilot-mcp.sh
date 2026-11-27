#!/usr/bin/env sh
set -eu

# SHA-256 of the published default version, so the jar is checked against a value from this repository and not
# only against a file from the same server. Another FXPILOT_VERSION is checked against FXPILOT_SHA256 or, without
# it, against the .sha256 file Maven Central publishes next to the jar.
DEFAULT_VERSION="1.1.1"
DEFAULT_SHA256="a74747a020d1e0be968e453943da7a3353a7d261f444f79bcdcdbe628598193d"

VERSION="${FXPILOT_VERSION:-$DEFAULT_VERSION}"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/fxpilot"
JAR="$CACHE_DIR/fxpilot-$VERSION.jar"
URL="https://repo1.maven.org/maven2/io/github/dbuedenbender/fxpilot/$VERSION/fxpilot-$VERSION.jar"

fetch() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$1" -o "$2"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$2" "$1"
    else
        echo "[fxpilot] curl or wget is required" >&2
        return 1
    fi
}

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -c1-64
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -c1-64
    else
        echo "[fxpilot] sha256sum or shasum is required to verify the download" >&2
        return 1
    fi
}

mkdir -p "$CACHE_DIR"
if [ ! -f "$JAR" ]; then
    echo "[fxpilot] downloading $URL" >&2
    # download next to the cache entry and move it into place only when complete and verified,
    # so an interrupted or tampered download is never started
    PART="$JAR.part.$$"
    trap 'rm -f "$PART" "$PART.sha256"' EXIT
    fetch "$URL" "$PART"
    EXPECTED="${FXPILOT_SHA256:-}"
    if [ -z "$EXPECTED" ] && [ "$VERSION" = "$DEFAULT_VERSION" ]; then
        EXPECTED="$DEFAULT_SHA256"
    fi
    if [ -z "$EXPECTED" ]; then
        if ! fetch "$URL.sha256" "$PART.sha256"; then
            echo "[fxpilot] no checksum for $URL; set FXPILOT_SHA256" >&2
            exit 1
        fi
        EXPECTED="$(cut -c1-64 "$PART.sha256")"
    fi
    ACTUAL="$(sha256 "$PART")"
    if [ "$(echo "$ACTUAL" | tr 'A-F' 'a-f')" != "$(echo "$EXPECTED" | tr 'A-F' 'a-f')" ]; then
        echo "[fxpilot] checksum mismatch for $URL" >&2
        exit 1
    fi
    mv "$PART" "$JAR"
    # exec below replaces the shell, so the EXIT trap would not run
    rm -f "$PART.sha256"
    trap - EXIT
fi

exec java -jar "$JAR"
