#!/usr/bin/env sh
set -eu

VERSION="${FXPILOT_VERSION:-1.1.0}"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/fxpilot"
JAR="$CACHE_DIR/fxpilot-$VERSION.jar"
URL="https://repo1.maven.org/maven2/io/github/dbuedenbender/fxpilot/$VERSION/fxpilot-$VERSION.jar"

mkdir -p "$CACHE_DIR"
if [ ! -f "$JAR" ]; then
    echo "[fxpilot] downloading $URL" >&2
    if command -v curl >/dev/null 2>&1; then
        curl -fL "$URL" -o "$JAR"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$JAR" "$URL"
    else
        echo "[fxpilot] curl or wget is required" >&2
        exit 1
    fi
fi

exec java -jar "$JAR"
