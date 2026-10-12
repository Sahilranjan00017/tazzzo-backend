#!/usr/bin/env bash
# Polls GET <base>/health/ready until 200 (default 5 minutes), then checks GET <base>/health/live.
#
#   scripts/uat-wait-healthy.sh <base-url> [timeout-seconds] [interval-seconds]
#
# FAIL FAST on an unmigrated schema: the backend starts in VERIFY mode and refuses an unmigrated or drifted schema, which
# /health/ready reports as components.datastore=REFUSED (docs/ops/HTTP_PLATFORM_BASELINE.md). That cannot heal by waiting,
# so after two consecutive REFUSED answers this exits 3 with a clear message. Migration APPLY is a separate human-authorised
# operation (docs/database/DATABASE_MIGRATION_RUNBOOK.md); this script never runs it. Prints statuses only, never bodies.
set -euo pipefail

BASE_URL="${1:-}"
TIMEOUT="${2:-300}"
INTERVAL="${3:-10}"
[ -n "$BASE_URL" ] || { echo "usage: $0 <base-url> [timeout] [interval]" >&2; exit 2; }
BASE_URL="${BASE_URL%/}"
command -v jq >/dev/null 2>&1 || { echo "missing required tool: jq" >&2; exit 2; }

body="$(mktemp)"
trap 'rm -f "$body"' EXIT
deadline=$((SECONDS + TIMEOUT))
refused=0
while :; do
  code="$(curl -sS --connect-timeout 5 --max-time 10 -o "$body" -w '%{http_code}' "${BASE_URL}/health/ready" 2>/dev/null)" || code="000"
  datastore="$(jq -r '.components.datastore // empty' "$body" 2>/dev/null || true)"
  echo "health: /health/ready status=${code} datastore=${datastore:-n/a}"
  if [ "$code" = 200 ]; then
    break
  fi
  if [ "$datastore" = REFUSED ]; then
    refused=$((refused + 1))
  else
    refused=0
  fi
  if [ "$refused" -ge 2 ]; then
    echo "FAIL FAST: the backend refused the datastore (datastore=REFUSED): the database schema is not migrated to the version this build expects, or it has drifted." >&2
    echo "Migration APPLY is a separate, human-authorised, two-key operation: see docs/database/DATABASE_MIGRATION_RUNBOOK.md and docs/ops/UAT_DEPLOYMENT.md. This pipeline does not run it." >&2
    exit 3
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "health: /health/ready did not return 200 within ${TIMEOUT}s" >&2
    exit 1
  fi
  sleep "$INTERVAL"
done

code="$(curl -sS --connect-timeout 5 --max-time 10 -o /dev/null -w '%{http_code}' "${BASE_URL}/health/live" 2>/dev/null)" || code="000"
echo "health: /health/live status=${code}"
[ "$code" = 200 ] || { echo "health: /health/live is not 200" >&2; exit 1; }
echo "health: OK"
