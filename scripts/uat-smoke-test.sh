#!/usr/bin/env bash
# UAT smoke test: read-only, unauthenticated checks against a deployed backend. Runnable locally against any base URL:
#
#   scripts/uat-smoke-test.sh https://api-uat.tazzzo.com
#   UAT_API_BASE_URL=http://localhost:8080 scripts/uat-smoke-test.sh
#
# It sends NO credentials and prints NO response bodies (only status codes and check names), so it cannot leak secrets.
# Every route is taken from docs/api/v1/openapi.yaml (and /api/v1/admin/me from ApiAuthFilter). Exit 0 only if all PASS.
# Optional: SMOKE_REPORT=<file> appends a markdown table (the UAT workflow points it at $GITHUB_STEP_SUMMARY).
# Requires: bash, curl, jq.
set -euo pipefail

BASE_URL="${1:-${UAT_API_BASE_URL:-}}"
if [ -z "$BASE_URL" ]; then
  echo "usage: $0 <base-url>   (or set UAT_API_BASE_URL)" >&2
  exit 2
fi
BASE_URL="${BASE_URL%/}"
case "$BASE_URL" in
  http://* | https://*) ;;
  *) echo "base url must start with http:// or https://" >&2; exit 2 ;;
esac
for tool in curl jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo "missing required tool: $tool" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
BODY="$TMP/body"
HDRS="$TMP/headers"
STATUS="000"

NAMES=()
RESULTS=()
DETAILS=()

# get <path> : GET with hard timeouts; sets STATUS, fills BODY and HDRS. Never aborts the script on a transport failure.
get() {
  STATUS="$(curl -sS --connect-timeout 5 --max-time 20 -o "$BODY" -D "$HDRS" -w '%{http_code}' \
    -H 'Accept: application/json' "${BASE_URL}$1" 2>/dev/null)" || STATUS="000"
  [ -f "$BODY" ] || : >"$BODY"
}

# header <name> : value of a response header from the last request (case-insensitive), CR stripped.
header() {
  grep -i "^$1:" "$HDRS" 2>/dev/null | tail -n 1 | cut -d: -f2- | tr -d '\r' | sed 's/^[[:space:]]*//' || true
}

# record <name> <PASS|FAIL> <detail>
record() {
  NAMES+=("$1")
  RESULTS+=("$2")
  DETAILS+=("$3")
}

json_ok() { jq -e . "$BODY" >/dev/null 2>&1; }

# flat error envelope: code + message strings and a request id (requestId on domain errors, request_id on platform ones)
flat_envelope_ok() {
  jq -e '(.code | type == "string") and (.message | type == "string") and ((.requestId // .request_id) | type == "string")' \
    "$BODY" >/dev/null 2>&1
}

check_live() {
  get /health/live
  if [ "$STATUS" = 200 ] && [ "$(jq -r '.status // empty' "$BODY" 2>/dev/null)" = UP ]; then
    record "GET /health/live" PASS "200 status=UP"
  else
    record "GET /health/live" FAIL "status=$STATUS"
  fi
}

check_ready() {
  get /health/ready
  if [ "$STATUS" = 200 ] && [ "$(jq -r '.status // empty' "$BODY" 2>/dev/null)" = UP ]; then
    record "GET /health/ready" PASS "200 status=UP"
  else
    record "GET /health/ready" FAIL "status=$STATUS (503 = datastore not open, schema not migrated, or dependency down)"
  fi
}

check_json_200() { # <path>
  get "$1"
  if [ "$STATUS" = 200 ] && json_ok; then
    record "GET $1" PASS "200 valid JSON"
  else
    record "GET $1" FAIL "status=$STATUS"
  fi
}

check_legal() { # <slug> : 200 when published, flat 404 when not published yet
  local path="/v1/content/legal/$1"
  get "$path"
  if [ "$STATUS" = 200 ] && json_ok; then
    record "GET $path" PASS "200 published"
  elif [ "$STATUS" = 404 ] && json_ok && flat_envelope_ok; then
    record "GET $path" PASS "404 not published (flat envelope)"
  else
    record "GET $path" FAIL "status=$STATUS"
  fi
}

check_serviceability() {
  local path="/v1/serviceability?pin=560001" # the PIN pattern in openapi is ^[1-9][0-9]{5}$
  get "$path"
  if [ "$STATUS" = 200 ] && jq -e '.serviceable | type == "boolean"' "$BODY" >/dev/null 2>&1; then
    record "GET /v1/serviceability?pin=<valid>" PASS "200 serviceable=$(jq -r '.serviceable' "$BODY")"
  elif { [ "$STATUS" = 400 ] || [ "$STATUS" = 429 ]; } && json_ok && flat_envelope_ok; then
    record "GET /v1/serviceability?pin=<valid>" PASS "$STATUS valid JSON error"
  else
    record "GET /v1/serviceability?pin=<valid>" FAIL "status=$STATUS (503 = rate limiter or geo provider unavailable)"
  fi
}

check_bad_request_envelope() {
  get "/v1/serviceability?pin=not-a-pin"
  if [ "$STATUS" = 400 ] && flat_envelope_ok; then
    record "Bad request -> flat error envelope" PASS "400 code=$(jq -r '.code' "$BODY")"
  else
    record "Bad request -> flat error envelope" FAIL "status=$STATUS or envelope shape wrong"
  fi
}

check_security_headers() {
  get /health/live
  local missing="" h
  for h in X-Content-Type-Options X-Frame-Options Referrer-Policy Content-Security-Policy Strict-Transport-Security; do
    [ -n "$(header "$h")" ] || missing="$missing $h"
  done
  if [ "$(header X-Content-Type-Options)" != nosniff ]; then missing="$missing X-Content-Type-Options=nosniff"; fi
  if [ -z "$missing" ]; then
    record "Security response headers" PASS "all 5 present"
  else
    record "Security response headers" FAIL "missing/wrong:$missing"
  fi
}

check_admin_requires_auth() {
  get /api/v1/admin/me
  if [ "$STATUS" = 401 ]; then
    record "Unauthenticated /api/v1/admin/me" PASS "401"
  else
    record "Unauthenticated /api/v1/admin/me" FAIL "status=$STATUS (expected 401)"
  fi
}

echo "UAT smoke test against: $BASE_URL"
check_live
check_ready
check_json_200 /v1/app-config
check_json_200 /v1/content/faqs
check_legal terms
check_legal privacy
check_serviceability
check_bad_request_envelope
check_security_headers
check_admin_requires_auth

fails=0
printf '\n%-42s %-6s %s\n' CHECK RESULT DETAIL
printf '%-42s %-6s %s\n' ------------------------------------------ ------ ------
for i in "${!NAMES[@]}"; do
  printf '%-42s %-6s %s\n' "${NAMES[$i]}" "${RESULTS[$i]}" "${DETAILS[$i]}"
  [ "${RESULTS[$i]}" = PASS ] || fails=$((fails + 1))
done

if [ -n "${SMOKE_REPORT:-}" ]; then
  {
    echo "| Check | Result | Detail |"
    echo "|---|---|---|"
    for i in "${!NAMES[@]}"; do
      echo "| ${NAMES[$i]} | ${RESULTS[$i]} | ${DETAILS[$i]} |"
    done
  } >>"$SMOKE_REPORT"
fi

echo
if [ "$fails" -eq 0 ]; then
  echo "SMOKE: PASS (${#NAMES[@]}/${#NAMES[@]})"
else
  echo "SMOKE: FAIL ($fails of ${#NAMES[@]} checks failed)"
  exit 1
fi
