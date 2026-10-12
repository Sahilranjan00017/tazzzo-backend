#!/usr/bin/env bash
# Offline self-test of scripts/uat-e2e-cod.sh against scripts/uat-e2e-mock-server.py (a local stand-in, 127.0.0.1 only).
# It needs no AWS, no UAT host and no secret. It checks:
#   - the guard rails: no URL, production hosts (several spellings), plain http, credentials and paths are refused (exit 2);
#   - --dry-run validates the plan against the OpenAPI document and fails when a planned route is missing;
#   - the happy path ends PASS (exit 0) and prints no token, grant, OTP or full phone number;
#   - every failure scenario of the mock makes the script exit 1 with the expected FAIL row.
# Usage: scripts/uat-e2e-cod-selftest.sh      (exit 0 only when every case behaves as expected)
set -uo pipefail

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
  expect_run "production host refused: $url" 2 "REFUSED: .* production host" UAT_API_BASE_URL="$url" UAT_TEST_PHONE="$TEST_PHONE" UAT_OTP="$GOOD_OTP"
done
expect_run "unknown tazzzo.com host without a uat label is refused" 2 "does not look like a UAT" UAT_API_BASE_URL=https://api-prod.tazzzo.com UAT_TEST_PHONE="$TEST_PHONE"
expect_run "plain http to a non-loopback host is refused" 2 "plain http is accepted only for a loopback" UAT_API_BASE_URL=http://api-uat.tazzzo.com UAT_TEST_PHONE="$TEST_PHONE"
expect_run "credentials in the URL are refused" 2 "must not contain credentials" UAT_API_BASE_URL=https://user:pw@api-uat.tazzzo.com UAT_TEST_PHONE="$TEST_PHONE"
expect_run "a path in the URL is refused" 2 "scheme://host" UAT_API_BASE_URL=https://api-uat.tazzzo.com/v1 UAT_TEST_PHONE="$TEST_PHONE"
expect_run "missing phone is refused (real run)" 2 "UAT_TEST_PHONE is required" UAT_API_BASE_URL=https://api-uat.tazzzo.com
expect_run "bad phone is refused" 2 "Indian mobile" UAT_API_BASE_URL=https://api-uat.tazzzo.com UAT_TEST_PHONE=12345
expect_run "bad OTP format is refused" 2 "exactly 6 digits" UAT_API_BASE_URL=https://api-uat.tazzzo.com UAT_TEST_PHONE="$TEST_PHONE" UAT_OTP=12ab

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
expect_run "happy path passes" 0 "RESULT: PASS" UAT_API_BASE_URL="http://127.0.0.1:$PORT" UAT_TEST_PHONE="$TEST_PHONE" UAT_OTP="$GOOD_OTP"
assert "happy path summary has 0 FAIL and 0 SKIP" grep -Eq "SUMMARY: [0-9]+ PASS, 0 FAIL, 0 SKIP" "$OUT"
for r in "COD order" "order replay" "stock moved once" "stock stable" "cart cleared" "token rotation" "old refresh refused" "revoked access refused" "revoked refresh refused"; do row "happy" "$r" PASS; done
no_secrets_in_output "happy"
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
