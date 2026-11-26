#!/usr/bin/env bash
# Starts the MCP server with the java on PATH and the given arguments (e.g. -jar target/fxpilot.jar) and checks
# initialize, tools/list and a tool call against an endpoint that is not running. CI runs it on JDK 11, the
# oldest runtime the jar is compiled for, which the test suite (JDK 21) does not cover.
set -euo pipefail

java -version 2>&1 | head -n 1
out="$(printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"fx_health","arguments":{}}}' \
  | FXPILOT_URL=http://127.0.0.1:1 timeout 60 java "$@")"

fail() {
  echo "MCP smoke test failed: $1" >&2
  echo "$out" >&2
  exit 1
}
[ "$(printf '%s\n' "$out" | wc -l)" -eq 3 ] || fail "expected 3 replies"
printf '%s\n' "$out" | sed -n 1p | grep -q '"protocolVersion":"2025-06-18"' || fail "initialize"
printf '%s\n' "$out" | sed -n 2p | grep -q '"name":"fx_health"' || fail "tools/list"
printf '%s\n' "$out" | sed -n 3p | grep -q 'Cannot reach the app' || fail "tools/call"
echo "MCP server OK"
