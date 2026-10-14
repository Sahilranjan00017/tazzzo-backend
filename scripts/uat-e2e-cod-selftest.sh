#!/usr/bin/env bash
# Offline self-test of scripts/uat-e2e-cod.sh against scripts/uat-e2e-mock-server.py (a local stand-in, 127.0.0.1 only).
# It needs no AWS, no UAT host and no secret. It checks:
#   - the guard rails: no URL, production hosts (several spellings), plain http, credentials and paths are refused (exit 2);
#   - --dry-run validates the plan against the OpenAPI document and fails when a planned route is missing;
#   - the happy path ends PASS (exit 0) and prints no token, grant, OTP or full phone number;
#   - every failure scenario of the mock makes the script exit 1 with the expected FAIL row.
# Usage: scripts/uat-e2e-cod-selftest.sh      (exit 0 only when every case behaves as expected)
# shellcheck disable=SC2317,SC2329  # helpers are called indirectly through assert/assert_not
set -uo pipefail
BS=$'\\'

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E="$HERE/uat-e2e-cod.sh"
MOCK="$HERE/uat-e2e-mock-server.py"
SPEC="$HERE/../docs/api/v1/openapi.yaml"
TMP="$(mktemp -d)"
SERVER_PID=""
stop_server() {
  if [ -n "$SERVER_PID" ]; then kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null; SERVER_PID=""; fi
}
trap 'stop_server; rm -rf "$TMP"' EXIT

for tool in python3 curl jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo "selftest: missing tool $tool" >&2; exit 2; }
done
# Nothing from the caller's environment may steer the cases.
while IFS= read -r v; do unset "$v"; done < <(env | sed -n 's/^\(UAT_[A-Za-z0-9_]*\)=.*/\1/p')

TEST_PHONE="+919876543210"
GOOD_OTP="482915"
FAILED=0
TOTAL=0
OUT="$TMP/out"
REAL_JQ="$(command -v jq)"
REAL_CURL="$(command -v curl)"
SHIM="$TMP/shim"
ARGV_LOG="$TMP/argv.log"
mkdir -p "$SHIM"
# Shims on PATH record the argv of every jq / curl the script starts, then run the real tool.
printf '#!/bin/sh\nprintf "%%s\\n" "jq $*" >>"%s"\nexec "%s" "$@"\n' "$ARGV_LOG" "$REAL_JQ" >"$SHIM/jq"
printf '#!/bin/sh\nprintf "%%s\\n" "curl $*" >>"%s"\nexec "%s" "$@"\n' "$ARGV_LOG" "$REAL_CURL" >"$SHIM/curl"
chmod +x "$SHIM/jq" "$SHIM/curl"
export PATH="$SHIM:$PATH"

requests_seen() { curl -s "http://127.0.0.1:$PORT/__stats" | jq -r .requests; }

start_server() { # <scenario>
  stop_server
  rm -f "$TMP/port" "$TMP/secrets"
  : >"$TMP/secrets"
  python3 -I "$MOCK" --port-file "$TMP/port" --secrets-file "$TMP/secrets" --scenario "$1" --otp "$GOOD_OTP" &
  SERVER_PID=$!
  local i=0
  while [ ! -s "$TMP/port" ] && [ "$i" -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
  [ -s "$TMP/port" ] || { echo "selftest: mock server did not start" >&2; exit 2; }
  PORT="$(cat "$TMP/port")"
}

check() { # <case name> <ok|bad> <detail>
  TOTAL=$((TOTAL + 1))
  if [ "$2" = ok ]; then
    echo "ok     - $1"
  else
    echo "NOT OK - $1: $3"
    FAILED=$((FAILED + 1))
  fi
}

assert() { # <case name> <command...> : ok when the command succeeds
  local name="$1"
  shift
  if "$@"; then check "$name" ok ""; else check "$name" bad "assertion failed: $*"; fi
}

assert_not() { # <case name> <command...> : ok when the command fails
  local name="$1"
  shift
  if "$@"; then check "$name" bad "unexpected success: $*"; else check "$name" ok ""; fi
}

# expect_run <name> <expected exit> <regex that must appear in the output (or '-')> -- <env assignments...>
# stdin is /dev/null so the script can never wait for a terminal.
expect_run() {
  local name="$1" want_exit="$2" want_re="$3" got
  shift 3
  if [ "${1:-}" = "--" ]; then shift; fi
  env "$@" bash "$E2E" </dev/null >"$OUT" 2>&1
  got=$?
  if [ "$got" != "$want_exit" ]; then
    check "$name" bad "exit $got, expected $want_exit"
    sed 's/^/        | /' "$OUT" | head -40
    return
  fi
  if [ "$want_re" != "-" ] && ! grep -Eq -- "$want_re" "$OUT"; then
    check "$name" bad "output lacks /$want_re/"
    sed 's/^/        | /' "$OUT" | head -40
    return
  fi
  check "$name" ok ""
}

no_secrets_in_output() { # <case name>
  local leaked=0 s
  while IFS= read -r s; do
    [ -n "$s" ] && grep -qF -- "$s" "$OUT" && leaked=1
  done <"$TMP/secrets"
  grep -qF -- "$GOOD_OTP" "$OUT" && leaked=1
  grep -qF -- "$TEST_PHONE" "$OUT" && leaked=1
  grep -qF -- "${TEST_PHONE#+91}" "$OUT" && leaked=1
  if [ "$leaked" = 0 ]; then check "$1: no token/grant/otp/phone in the output" ok ""; else check "$1: no token/grant/otp/phone in the output" bad "a secret value was printed"; fi
}

row() { # <case name> <check name> <RESULT>  : the table has this row with this result
  if grep -Eq "^$2 +$3( |$)" "$OUT"; then check "$1: row '$2' is $3" ok ""; else check "$1: row '$2' is $3" bad "row not found"; sed 's/^/        | /' "$OUT" | head -40; fi
}

# --- guard rails (no server needed) ------------------------------------------------------------------------------------
echo "== guard rails =="
expect_run "no UAT_API_BASE_URL is refused" 2 "UAT_API_BASE_URL is required" -- UAT_TEST_PHONE="$TEST_PHONE"
for url in https://api.tazzzo.com https://API.TAZZZO.COM. https://tazzzo.com https://www.tazzzo.com https://admin.tazzzo.com https://cms.tazzzo.com; do
  expect_run "production host refused: $url" 2 "REFUSED" UAT_API_BASE_URL="$url" UAT_TEST_PHONE="$TEST_PHONE" UAT_OTP="$GOOD_OTP"
done
expect_run "unknown tazzzo.com host without a uat label is refused" 2 "does not look like a UAT" UAT_API_BASE_URL=https://api-prod.tazzzo.com UAT_TEST_PHONE="$TEST_PHONE"
expect_run "plain http to a non-loopback host is refused" 2 "plain http is accepted only for localhost" UAT_API_BASE_URL=http://api-uat.tazzzo.com UAT_TEST_PHONE="$TEST_PHONE"
expect_run "credentials in the URL are refused" 2 "REFUSED: UAT_API_BASE_URL must be scheme" UAT_API_BASE_URL=https://user:pw@api-uat.tazzzo.com UAT_TEST_PHONE="$TEST_PHONE"
expect_run "a path in the URL is refused" 2 "scheme://host" UAT_API_BASE_URL=https://api-uat.tazzzo.com/v1 UAT_TEST_PHONE="$TEST_PHONE"
expect_run "missing phone is refused (real run)" 2 "UAT_TEST_PHONE is required" UAT_API_BASE_URL=https://api-uat.tazzzo.com
expect_run "bad phone is refused" 2 "Indian mobile" UAT_API_BASE_URL=https://api-uat.tazzzo.com UAT_TEST_PHONE=12345
expect_run "bad OTP format is refused" 2 "exactly 6 digits" UAT_API_BASE_URL=https://api-uat.tazzzo.com UAT_TEST_PHONE="$TEST_PHONE" UAT_OTP=12ab

# Hostile spellings of the production host (and look-alikes). Each must exit 2 BEFORE any curl is started, even with a
# phone and OTP present. The loopback look-alikes would reach the mock server if the parser were lenient: its counter must stay 0.
echo "== hostile URL spellings =="
start_server happy
refused() { # <url>
  : >"$ARGV_LOG"
  expect_run "refused before any network call: $1" 2 "REFUSED|UAT_API_BASE_URL" UAT_API_BASE_URL="$1" UAT_TEST_PHONE="$TEST_PHONE" UAT_OTP="$GOOD_OTP" "${@:2}"
  assert_not "no curl was started for: $1" grep -q '^curl ' "$ARGV_LOG"
}
for url in \
  'https://api%2Etazzzo%2Ecom' 'https://api%2etazzzo%2ecom' 'https://api.tazzzo.com%2E' 'https://api.tazzzo.com%2e' \
  'https://api%252Etazzzo%252Ecom' 'https://api.tazzzo.com%252E' 'https://api%2Etazzzo.com' 'https://%61pi.tazzzo.com' \
  'https://api-uat%2Etazzzo%2Ecom' \
  "https://api.tazzzo.com${BS}" "https://api.tazzzo.com${BS}@api-uat.tazzzo.com" "https://api-uat.tazzzo.com${BS}.evil.com" \
  'https://api．tazzzo．com' 'https://ａｐｉ.tazzzo.com' 'https://api-uat.tazzzo.com。evil' \
  'https://api.tazzzo.com.' 'https://API.TAZZZO.COM.' 'https://api-uat.tazzzo.com.' 'https://API-UAT.TAZZZO.COM.' \
  'https://API.TAZZZO.COM' 'https://Api.Tazzzo.Com' \
  'https://api-uat.tazzzo.com.evil.com' 'https://uat.tazzzo.com.evil.com' 'https://evil-api-uat.tazzzo.com.evil.com' 'https://tazzzo.com.evil.com' \
  'https://evil.com' 'https://203.0.113.5' 'https://203.0.113.5:8443' 'https://[::1]' 'https://[2001:db8::1]:443' \
  'https://api-uat..tazzzo.com' 'https://.api-uat.tazzzo.com' 'https://-api-uat.tazzzo.com' 'https://api-uat-.tazzzo.com' \
  'https://api.tazzzo.com:443' 'https://api.tazzzo.com:' 'https://api-uat.tazzzo.com:' 'https://api-uat.tazzzo.com:abc' \
  'https://api-uat.tazzzo.com:0' 'https://api-uat.tazzzo.com:99999' 'https://api-uat.tazzzo.com:44 3' 'https://api-uat.tazzzo.com:443:443' \
  'https://api-uat.tazzzo.com ' 'https://api-uat.tazzzo.com//' 'https://api-uat.tazzzo.com?x=1' 'https://api-uat.tazzzo.com#x' \
  'https://user@api-uat.tazzzo.com' 'https://api.tazzzo.com@api-uat.tazzzo.com' 'http://api-uat.tazzzo.com' 'http://203.0.113.5' \
  'ftp://api-uat.tazzzo.com' 'api-uat.tazzzo.com' 'https://' 'https://:443'; do
  refused "$url"
done
# The same for the production names even with the explicit foreign-host switch.
refused 'https://api%2Etazzzo%2Ecom' UAT_ALLOW_FOREIGN_HOST=1
refused 'https://api.tazzzo.com' UAT_ALLOW_FOREIGN_HOST=1
refused 'https://API.TAZZZO.COM.' UAT_ALLOW_FOREIGN_HOST=1
refused "https://api.tazzzo.com.evil.com${BS}" UAT_ALLOW_FOREIGN_HOST=1
# Loopback look-alikes that a lenient parser would send to the mock server.
for url in "http://127.0.0.1%2e:$PORT" "http://127.0.0.1.:$PORT" "http://127.0.0.1\\@evil.com:$PORT" "http://127.0.0.1:$PORT@evil.com" "http://127.0.0.1:$PORT/x" "http://0.0.0.0:$PORT" "http://127.0.0.2:$PORT" "http://localhost.evil.com:$PORT" "http://[::1]:$PORT"; do
  refused "$url"
done
assert "the mock server saw 0 requests during all hostile URLs" test "$(requests_seen)" = 0
# Foreign host: only with the explicit switch, with a warning; dry-run needs no network.
env UAT_API_BASE_URL=https://uat-stack.example.org UAT_ALLOW_FOREIGN_HOST=1 bash "$E2E" --dry-run </dev/null >"$OUT" 2>&1; rc=$?
assert "foreign host with UAT_ALLOW_FOREIGN_HOST=1 passes the dry run" test "$rc" = 0
assert "... and prints a warning" grep -q 'WARNING: UAT_ALLOW_FOREIGN_HOST=1' "$OUT"
env UAT_API_BASE_URL=https://uat-stack.example.org bash "$E2E" --dry-run </dev/null >"$OUT" 2>&1; rc=$?
assert "foreign host without the switch is refused" test "$rc" = 2
for url in https://API-UAT.TAZZZO.COM https://api-uat.tazzzo.com/ https://api-uat.tazzzo.com:8443 https://uat.tazzzo.com https://cms-uat.tazzzo.com https://admin-uat.tazzzo.com; do
  env UAT_API_BASE_URL="$url" bash "$E2E" --dry-run </dev/null >"$OUT" 2>&1; rc=$?
  assert "allowed UAT spelling accepted (dry run): $url" test "$rc" = 0
done
stop_server

# --- dry run -----------------------------------------------------------------------------------------------------------
echo "== dry run =="
dry() { env "$@" bash "$E2E" --dry-run </dev/null >"$OUT" 2>&1; }
dry UAT_API_BASE_URL=https://api-uat.tazzzo.com UAT_TEST_PHONE="$TEST_PHONE"; rc=$?
if [ "$rc" = 0 ] && grep -q "DRY RUN OK" "$OUT" && ! grep -q MISSING "$OUT"; then check "dry-run on the UAT host passes against the real OpenAPI file" ok ""; else check "dry-run on the UAT host passes against the real OpenAPI file" bad "exit $rc"; sed 's/^/        | /' "$OUT" | head -50; fi
dry UAT_API_BASE_URL=https://admin-uat.tazzzo.com UAT_TEST_PHONE="$TEST_PHONE"; rc=$?
assert "dry-run accepts admin-uat.tazzzo.com (carries 'uat')" test "$rc" = 0
dry UAT_API_BASE_URL=https://api.tazzzo.com; rc=$?
assert "dry-run refuses the production host" test "$rc" = 2
dry UAT_API_BASE_URL=https://api-uat.tazzzo.com; rc=$?
assert "dry-run needs no phone and no OTP" test "$rc" = 0
# A spec without the order endpoint: the dry run must fail and name the route.
grep -v '/v1/customer/orders:' "$SPEC" >"$TMP/spec-trimmed.yaml"
sed -i.bak 's#^  /v1/customer/orders/{orderId}:#  /v1/customer/orders-renamed/{orderId}:#' "$TMP/spec-trimmed.yaml"
dry UAT_API_BASE_URL=https://api-uat.tazzzo.com UAT_OPENAPI_FILE="$TMP/spec-trimmed.yaml"; rc=$?
if [ "$rc" = 1 ] && grep -q MISSING "$OUT" && grep -q "DRY RUN FAILED" "$OUT"; then check "dry-run fails when a planned route is not in the OpenAPI file" ok ""; else check "dry-run fails when a planned route is not in the OpenAPI file" bad "exit $rc"; fi

# --- happy path --------------------------------------------------------------------------------------------------------
echo "== happy path =="
start_server happy
: >"$ARGV_LOG"
expect_run "happy path passes" 0 "RESULT: PASS" UAT_API_BASE_URL="http://127.0.0.1:$PORT" UAT_TEST_PHONE="$TEST_PHONE" UAT_OTP="$GOOD_OTP"
assert "happy path summary has 0 FAIL and 0 SKIP" grep -Eq "SUMMARY: [0-9]+ PASS, 0 FAIL, 0 SKIP" "$OUT"
for r in "COD order" "order replay" "stock moved once" "stock stable" "cart cleared" "token rotation" "old refresh refused" "revoked access refused" "revoked refresh refused"; do row "happy" "$r" PASS; done
no_secrets_in_output "happy"
secret_free_argv() { # no secret value in the argv of any jq / curl the script started
  local leaked=0 s
  while IFS= read -r s; do
    if [ -n "$s" ] && grep -qF -- "$s" "$ARGV_LOG"; then leaked=1; fi
  done <"$TMP/secrets"
  if grep -qF -- "$GOOD_OTP" "$ARGV_LOG" || grep -qF -- "$TEST_PHONE" "$ARGV_LOG" || grep -qF -- "${TEST_PHONE#+91}" "$ARGV_LOG"; then leaked=1; fi
  [ "$leaked" = 0 ]
}
assert "argv of jq/curl children holds no token, grant, OTP or phone (happy run)" secret_free_argv
assert "the argv log is not empty (the shims were really used)" test -s "$ARGV_LOG"
UAT_REPORT_FILE="$TMP/report.md"
expect_run "happy path writes a markdown report" 0 "RESULT: PASS" UAT_API_BASE_URL="http://127.0.0.1:$PORT" UAT_TEST_PHONE="$TEST_PHONE" UAT_OTP="$GOOD_OTP" UAT_REPORT="$UAT_REPORT_FILE" UAT_ORDER_QTY=2
assert "report file holds the table" grep -q '^| COD order | PASS' "$UAT_REPORT_FILE"
assert_not "report file holds no OTP" grep -qF -- "$GOOD_OTP" "$UAT_REPORT_FILE"

# --- failure paths -----------------------------------------------------------------------------------------------------
echo "== failure paths =="
fail_case() { # <scenario> <expected FAIL row> [extra env...]
  local scen="$1" rowname="$2"
  shift 2
  start_server "$scen"
  expect_run "scenario $scen exits 1" 1 "RESULT: FAIL" UAT_API_BASE_URL="http://127.0.0.1:$PORT" UAT_TEST_PHONE="$TEST_PHONE" UAT_OTP="$GOOD_OTP" "$@"
  row "$scen" "$rowname" FAIL
  no_secrets_in_output "$scen"
}
fail_case not-serviceable "serviceability"
fail_case anonymous-open "anonymous refused"
fail_case no-products "browse products"
fail_case order-500 "COD order"
fail_case replay-new-order "order replay"
fail_case stock-not-moved "stock moved once"
fail_case stock-double-move "stock moved once"
fail_case cart-not-cleared "cart cleared"
fail_case refresh-reuse-ok "old refresh refused"
fail_case logout-not-revoking "revoked access refused"
# a failing step skips the rest and still revokes the session
start_server order-500
expect_run "after a failure the later steps are SKIP and the session is revoked" 1 "cleanup logout +INFO +session revoked" UAT_API_BASE_URL="http://127.0.0.1:$PORT" UAT_TEST_PHONE="$TEST_PHONE" UAT_OTP="$GOOD_OTP"
row "order-500" "order history" SKIP

# capped stock: the public API cannot prove the movement -> SKIP, or FAIL when proof is required
start_server capped-stock
expect_run "capped stock: SKIP, exit 0" 0 "RESULT: PASS" UAT_API_BASE_URL="http://127.0.0.1:$PORT" UAT_TEST_PHONE="$TEST_PHONE" UAT_OTP="$GOOD_OTP"
row "capped-stock" "stock moved once" SKIP
start_server capped-stock
expect_run "capped stock with UAT_REQUIRE_STOCK_PROOF=1: FAIL" 1 "RESULT: FAIL" UAT_API_BASE_URL="http://127.0.0.1:$PORT" UAT_TEST_PHONE="$TEST_PHONE" UAT_OTP="$GOOD_OTP" UAT_REQUIRE_STOCK_PROOF=1
row "capped-stock+proof" "stock moved once" FAIL

# wrong OTP and no OTP at all
start_server happy
expect_run "wrong OTP fails at otp verify" 1 "RESULT: FAIL" UAT_API_BASE_URL="http://127.0.0.1:$PORT" UAT_TEST_PHONE="$TEST_PHONE" UAT_OTP=000000
row "wrong-otp" "otp verify" FAIL
row "wrong-otp" "session" SKIP
no_secrets_in_output "wrong-otp"
start_server happy
expect_run "no OTP and no terminal fails at otp verify" 1 "no OTP: set UAT_OTP" UAT_API_BASE_URL="http://127.0.0.1:$PORT" UAT_TEST_PHONE="$TEST_PHONE"

# nothing listening
stop_server
expect_run "unreachable host fails at health" 1 "no HTTP answer" UAT_API_BASE_URL="http://127.0.0.1:9" UAT_TEST_PHONE="$TEST_PHONE" UAT_OTP="$GOOD_OTP"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "SELFTEST PASS: $TOTAL checks"
  exit 0
fi
echo "SELFTEST FAIL: $FAILED of $TOTAL checks failed" >&2
exit 1
