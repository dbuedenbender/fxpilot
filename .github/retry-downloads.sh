#!/usr/bin/env bash
# Runs a command up to three times, but retries only when the log shows a failed download (Maven Central
# rate limits, network resets). A test or replay failure fails at once, so flaky tests stay visible.
set -uo pipefail

log="$(mktemp)"
for attempt in 1 2 3; do
  if "$@" 2>&1 | tee "$log"; then
    exit 0
  fi
  if ! grep -Eq "Could not transfer|Could not resolve|status code: (429|5[0-9][0-9])|Connection reset|Read timed out|Connect timed out" "$log"; then
    exit 1
  fi
  echo "Download problem (attempt ${attempt}), retrying in 15 s..."
  sleep 15
done
exit 1
