#!/usr/bin/env bash
# Starts both example apps headless (Monocle) and replays their plans against them.
#   demo-app       classpath, FxPilot as a library dependency
#   modular-demo   module path, FxPilot as a JPMS module ("requires io.github.dbuedenbender.fxpilot")
# Needs the current FxPilot installed (mvn -DskipTests install) and JDK 17+. Used by CI; runs locally too.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)
SEP=":"
JAVA=(java)
case "$(uname -s)" in
    # Git Bash on Windows: ';' separates paths, and the path lists must reach java unconverted
    MINGW*|MSYS*|CYGWIN*) SEP=";"; JAVA=(env MSYS_NO_PATHCONV=1 java) ;;
esac
MONOCLE_VERSION=21.0.2
WORK=target/headless
mkdir -p "$WORK"
EXTRA_JVM=${EXTRA_JVM:-}

mvn -B -q dependency:copy -Dartifact="org.testfx:openjfx-monocle:$MONOCLE_VERSION" -DoutputDirectory="$WORK"
MONOCLE="$WORK/openjfx-monocle-$MONOCLE_VERSION.jar"
HEADLESS="-Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw -Dheadless.geometry=1920x1200-32"

wait_for_endpoint() { # port log
    for _ in $(seq 1 60); do
        if grep -q "listening on" "$2" 2>/dev/null; then return 0; fi
        sleep 1
    done
    echo "endpoint on port $1 did not start:"; cat "$2"; return 1
}

replay() { # port plan...
    local port=$1; shift
    for plan in "$@"; do
        # shellcheck disable=SC2086
        "${JAVA[@]}" $EXTRA_JVM -jar target/fxpilot.jar --replay "$plan" --port "$port" --delay 0
    done
}

PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

# --- demo-app on the class path
mvn -B -q -f examples/demo-app/pom.xml package dependency:build-classpath -Dmdep.outputFile="$ROOT/$WORK/demo-cp.txt"
# shellcheck disable=SC2086
"${JAVA[@]}" $EXTRA_JVM $HEADLESS -Dfxpilot.port=8781 \
    -cp "examples/demo-app/target/classes$SEP$(cat "$WORK/demo-cp.txt")$SEP$MONOCLE" \
    io.github.dbuedenbender.fxpilot.demo.Launcher > "$WORK/demo.log" 2>&1 &
PIDS+=($!)
wait_for_endpoint 8781 "$WORK/demo.log"
replay 8781 examples/plans/demo-smoke.json examples/plans/demo-orders.json

# --- modular-demo on the module path; Monocle is patched into javafx.graphics
mvn -B -q -f examples/modular-demo/pom.xml package dependency:copy-dependencies -DincludeScope=runtime \
    -DoutputDirectory="$ROOT/$WORK/mods"
# shellcheck disable=SC2086
"${JAVA[@]}" $EXTRA_JVM $HEADLESS -Dfxpilot.port=8782 \
    --module-path "examples/modular-demo/target/fxpilot-modular-demo-1.1.0.jar$SEP$WORK/mods" \
    --patch-module "javafx.graphics=$MONOCLE" \
    -m io.github.dbuedenbender.fxpilot.modulardemo/io.github.dbuedenbender.fxpilot.modulardemo.ModularDemoApp \
    > "$WORK/modular.log" 2>&1 &
PIDS+=($!)
wait_for_endpoint 8782 "$WORK/modular.log"
replay 8782 examples/plans/modular-smoke.json

echo "all example plans passed"
