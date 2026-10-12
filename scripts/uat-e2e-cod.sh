#!/usr/bin/env bash
# UAT customer Cash-on-Delivery end-to-end API script (runbook section M, the customer half).
#
# It drives the REAL customer API of ONE backend through the whole purchase path and prints a PASS/FAIL table:
#   health, anonymous refusal, serviceability, catalogue browse, product detail + stock BEFORE, OTP login, profile, address,
#   cart, checkout quote, COD order, idempotent replay, order history, stock AFTER, cart cleared, token rotation (old refresh
#   token refused), address clean-up, logout revocation (access and refresh refused).
# Every route comes from docs/api/v1/openapi.yaml; --dry-run checks that each planned route still exists there.
#
# THIS SCRIPT WRITES to the target (a customer, an address, a cart and one COD order). Run it only against UAT.
#   - UAT_API_BASE_URL is required and has no default. A host on the production list (PRODUCTION_HOSTS below) is refused,
#     and so is any other *.tazzzo.com host whose name does not carry uat/staging/stg/dev/test.
#   - Plain http is accepted only for a loopback host (the local mock server in scripts/uat-e2e-cod-selftest.sh).
#
# Usage
#   UAT_API_BASE_URL=https://api-uat.tazzzo.com UAT_TEST_PHONE=+919876543210 scripts/uat-e2e-cod.sh
#   UAT_API_BASE_URL=https://api-uat.tazzzo.com scripts/uat-e2e-cod.sh --dry-run     # no network, validates the plan
#
# Environment
#   UAT_API_BASE_URL   required. https://host (no path, query or credentials).
#   UAT_TEST_PHONE     required for a real run. An Indian mobile number: +91XXXXXXXXXX, 10 digits, or 0XXXXXXXXXX.
#   UAT_OTP            optional. The 6-digit code for the test number, when the OTP gateway can deliver or fix it. If unset,
#                      the script PROMPTS on the terminal (input hidden) after the OTP request succeeded. Without a
#                      terminal and without UAT_OTP the run fails at otp verify. The code is never printed or logged.
#                      NOTE: the backend's provider-mode LOGGING never writes the code to any log (and is refused when
#                      TAZZZO_MIGRATION_ENVIRONMENT=staging): see docs/ops/UAT_ENVIRONMENT_CONTRACT.md section 5.
#   UAT_PIN            serviceable PIN of the test data (default 560001, the smoke-test PIN).
#   UAT_SKU_ID         optional product (TZP-...). Default: the first buyable product found by browsing from the root.
#   UAT_ORDER_QTY      units to order, 1..20 (default 1).
#   UAT_REQUIRE_STOCK_PROOF=1  turn "stock movement not observable through the public API" from SKIP into FAIL.
#   UAT_REPORT         optional file: the table is also appended there as markdown (for example $GITHUB_STEP_SUMMARY).
#
# Output rules: tokens, grant ids, the OTP and the full phone number are never printed; the table holds statuses, ids of
# non-secret kind (SKU, order id) and error CODES only. No response body is ever echoed. Tracing is forced off.
# Exit codes: 0 all steps PASS (SKIP allowed), 1 at least one FAIL, 2 usage / configuration / production-host refusal.
# Requires: bash 3.2+, curl 7.55+, jq. (No GNU-only tools: it also runs on macOS.)
# shellcheck disable=SC2329  # the step functions are called indirectly by step()
set +x
unset BASH_XTRACEFD
set -uo pipefail
umask 077

# ---------------------------------------------------------------------------------------------------------------------
# The ONE place that names production. Anything listed here is refused; add a host here if production grows one.
PRODUCTION_HOSTS=(
  api.tazzzo.com
  tazzzo.com
  www.tazzzo.com
  admin.tazzzo.com
  cms.tazzzo.com
  app.tazzzo.com
)
# Any other host under this domain must look like a non-production one (a label equal to one of these words).
TAZZZO_DOMAIN_SUFFIX=".tazzzo.com"
NONPROD_LABEL_RE='(^|[.-])(uat|staging|stg|dev|test)([.-]|$)'
# ---------------------------------------------------------------------------------------------------------------------

PIN_RE='^[1-9][0-9]{5}$'
SKU_RE='^TZP-[A-Za-z0-9-]{1,40}$'
PHONE_E164_RE='^\+91[6-9][0-9]{9}$'
PHONE_LOCAL_RE='^0?[6-9][0-9]{9}$'
OTP_RE='^[0-9]{6}$'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPENAPI="${UAT_OPENAPI_FILE:-$SCRIPT_DIR/../docs/api/v1/openapi.yaml}"

# The planned calls: "step|METHOD|path template as in the OpenAPI document|auth|purpose".
PLAN=(
  "health|GET|/health/live|none|liveness"
  "health|GET|/health/ready|none|readiness (datastore open, schema verified)"
  "anonymous|GET|/v1/customer/profile|none|anonymous customer call is refused (401)"
  "serviceability|GET|/v1/serviceability|none|test PIN is serviceable"
  "browse|GET|/v1/categories|none|root categories"
  "browse|GET|/v1/categories/{id}/children|none|walk down the tree"
  "browse|GET|/v1/categories/{id}/products|none|find a buyable product"
  "product|GET|/v1/products/{id}|none|product detail and stock BEFORE the order"
  "otp_request|POST|/v1/auth/otp/request|none|request an OTP for the test number"
  "otp_verify|POST|/v1/auth/otp/verify|none|verify the OTP (read from UAT_OTP or the terminal)"
  "session|POST|/v1/auth/session|none|exchange the grant for access + refresh tokens"
  "profile|GET|/v1/customer/profile|bearer|read profile"
  "profile|PATCH|/v1/customer/profile|bearer|set display name (If-Match)"
  "address|POST|/v1/customer/addresses|bearer|create the delivery address (Idempotency-Key)"
  "address|GET|/v1/customer/addresses|bearer|address is listed"
  "address|PUT|/v1/customer/addresses/{addressId}/default|bearer|make it the default"
  "cart|GET|/v1/customer/cart|bearer|read (and empty if needed) the cart"
  "cart|DELETE|/v1/customer/cart|bearer|clear a leftover cart (only when not empty)"
  "cart|PUT|/v1/customer/cart/items/{skuId}|bearer|set the exact quantity (If-Match)"
  "quote|POST|/v1/customer/checkout/quote|bearer|checkout quote (If-Match, Idempotency-Key)"
  "order|POST|/v1/customer/orders|bearer|place the COD order"
  "order|POST|/v1/customer/orders|bearer|replay the same quote: same order, no second stock move"
  "orders|GET|/v1/customer/orders|bearer|order history lists the order"
  "orders|GET|/v1/customer/orders/{orderId}|bearer|order detail"
  "stock|GET|/v1/products/{id}|none|stock AFTER the order (moved exactly once)"
  "cart_cleared|GET|/v1/customer/cart|bearer|cart is empty after the order"
  "refresh|POST|/v1/auth/refresh|none|rotate the refresh token; the old one is refused"
  "cleanup|GET|/v1/customer/addresses/{addressId}|bearer|read the E2E address version"
  "cleanup|DELETE|/v1/customer/addresses/{addressId}|bearer|remove the E2E address (If-Match)"
  "logout|POST|/v1/auth/logout|bearer|revoke the session"
  "logout|GET|/v1/customer/profile|bearer|the revoked access token is refused (401)"
  "logout|POST|/v1/auth/refresh|none|the revoked refresh token is refused (401)"
)

DRY_RUN=0
case "${1:-}" in
  "") ;;
  --dry-run) DRY_RUN=1 ;;
  -h | --help)
    sed -n '2,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  *)
    echo "usage: $0 [--dry-run]" >&2
    exit 2
    ;;
esac

die() { echo "uat-e2e-cod: $*" >&2; exit 2; }

for tool in curl jq; do
  command -v "$tool" >/dev/null 2>&1 || die "missing required tool: $tool"
done

# --- configuration and the production guard --------------------------------------------------------------------------
BASE_URL="${UAT_API_BASE_URL:-}"
[ -n "$BASE_URL" ] || die "UAT_API_BASE_URL is required (no default, by design). Example: UAT_API_BASE_URL=https://api-uat.tazzzo.com"
BASE_URL="${BASE_URL%/}"

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

TARGET_HOST=""
validate_base_url() {
  local url="$1" scheme rest hostport host p
  case "$url" in
    https://*) scheme=https; rest="${url#https://}" ;;
    http://*) scheme=http; rest="${url#http://}" ;;
    *) die "UAT_API_BASE_URL must start with https:// (or http:// for a loopback host)" ;;
  esac
  case "$rest" in
    */* | *\?* | *\#*) die "UAT_API_BASE_URL must be scheme://host[:port] only (no path, query or fragment)" ;;
    *@*) die "UAT_API_BASE_URL must not contain credentials" ;;
  esac
  hostport="$rest"
  case "$hostport" in
    \[*\]*) host="${hostport%%]*}"; host="${host#[}" ;;
    *) host="${hostport%%:*}" ;;
  esac
  host="$(lower "$host")"
  host="${host%.}" # "api.tazzzo.com." (absolute form) is the same host
  [ -n "$host" ] || die "UAT_API_BASE_URL has no host"
  for p in "${PRODUCTION_HOSTS[@]}"; do
    if [ "$host" = "$p" ]; then
      die "REFUSED: $host is a production host (PRODUCTION_HOSTS). This script writes data and runs against UAT only."
    fi
  done
  case "$host" in
    localhost | 127.0.0.1 | ::1) ;;
    *)
      [ "$scheme" = https ] || die "REFUSED: plain http is accepted only for a loopback host"
      case "$host" in
        *"$TAZZZO_DOMAIN_SUFFIX")
          if ! [[ $host =~ $NONPROD_LABEL_RE ]]; then
            die "REFUSED: $host is under ${TAZZZO_DOMAIN_SUFFIX#.} but does not look like a UAT/staging/dev host"
          fi
          ;;
      esac
      ;;
  esac
  TARGET_HOST="$host"
}
validate_base_url "$BASE_URL"

PIN="${UAT_PIN:-560001}"
[[ $PIN =~ $PIN_RE ]] || die "UAT_PIN must be a 6-digit PIN"
QTY="${UAT_ORDER_QTY:-1}"
if ! [[ $QTY =~ ^[0-9]+$ ]] || [ "$QTY" -lt 1 ] || [ "$QTY" -gt 20 ]; then die "UAT_ORDER_QTY must be 1..20"; fi
SKU_PRESET="${UAT_SKU_ID:-}"
if [ -n "$SKU_PRESET" ]; then
  [[ $SKU_PRESET =~ $SKU_RE ]] || die "UAT_SKU_ID must look like TZP-..."
fi

PHONE="${UAT_TEST_PHONE:-}"
PHONE_MASKED="(not set)"
if [ -n "$PHONE" ]; then
  if [[ $PHONE =~ $PHONE_E164_RE ]]; then
    :
  elif [[ $PHONE =~ $PHONE_LOCAL_RE ]]; then
    PHONE="+91${PHONE: -10}"
  else
    die "UAT_TEST_PHONE must be an Indian mobile number (+91XXXXXXXXXX)"
  fi
  PHONE_MASKED="+91******${PHONE: -2}"
elif [ "$DRY_RUN" = 0 ]; then
  die "UAT_TEST_PHONE is required (the number that receives the OTP)"
fi
# Take the OTP out of the environment at once so no child process (curl) ever inherits it.
OTP_PRESET="${UAT_OTP:-}"
unset UAT_OTP
if [ -n "$OTP_PRESET" ]; then
  [[ $OTP_PRESET =~ $OTP_RE ]] || die "UAT_OTP must be exactly 6 digits"
fi
REQUIRE_STOCK_PROOF="${UAT_REQUIRE_STOCK_PROOF:-0}"
REPORT_FILE="${UAT_REPORT:-}"

# --- results ----------------------------------------------------------------------------------------------------------
NAMES=()
RESULTS=()
DETAILS=()
record() { NAMES+=("$1"); RESULTS+=("$2"); DETAILS+=("$3"); }
pass() { record "$1" PASS "${2:-}"; return 0; }
fail() { record "$1" FAIL "${2:-}"; return 1; }
skip() { record "$1" SKIP "${2:-}"; return 0; }

# Secrets held by this run. scrub() removes them from any text before it is printed (defence in depth: no code path
# prints them, this makes sure a future mistake cannot).
ACCESS_TOKEN="" REFRESH_TOKEN="" GRANT_ID="" OTP=""
scrub() {
  local s="$1"
  if [ -n "$ACCESS_TOKEN" ]; then s="${s//"$ACCESS_TOKEN"/***}"; fi
  if [ -n "$REFRESH_TOKEN" ]; then s="${s//"$REFRESH_TOKEN"/***}"; fi
  if [ -n "$GRANT_ID" ]; then s="${s//"$GRANT_ID"/***}"; fi
  if [ -n "$OTP" ]; then s="${s//"$OTP"/***}"; fi
  if [ -n "$OTP_PRESET" ]; then s="${s//"$OTP_PRESET"/***}"; fi
  if [ -n "$PHONE" ]; then s="${s//"$PHONE"/$PHONE_MASKED}"; fi
  printf '%s' "$s"
}

# --- plan / dry run ---------------------------------------------------------------------------------------------------
spec_has() { # METHOD path-template -> 0 when the OpenAPI document declares it
  local want_m
  want_m="    $(lower "$1"):"
  awk -v want_path="  $2:" -v want_m="$want_m" '
    /^  \// { inpath = ($0 == want_path); next }
    inpath && $0 == want_m { found = 1 }
    END { exit found ? 0 : 1 }' "$OPENAPI"
}

print_plan() {
  local spec_state line step method path auth purpose n=0 bad=0 mark
  if [ -f "$OPENAPI" ]; then spec_state=present; else spec_state=missing; fi
  echo "Target       : $TARGET_HOST   (scheme/host verified; production list not matched)"
  echo "Test number  : $PHONE_MASKED"
  echo "PIN / qty    : $PIN / $QTY"
  echo "Product      : ${SKU_PRESET:-first buyable product found by browsing}"
  if [ -n "$OTP_PRESET" ]; then
    echo "OTP source   : UAT_OTP environment variable (value not shown)"
  elif [ -t 0 ]; then
    echo "OTP source   : interactive prompt (input hidden)"
  else
    echo "OTP source   : NONE (no UAT_OTP and no terminal): a real run would FAIL at otp verify"
  fi
  echo "OpenAPI file : $OPENAPI ($spec_state)"
  echo
  printf '%-3s %-15s %-7s %-47s %-7s %-8s %s\n' "#" "STEP" "METHOD" "PATH" "AUTH" "IN SPEC" "PURPOSE"
  for line in "${PLAN[@]}"; do
    n=$((n + 1))
    IFS='|' read -r step method path auth purpose <<<"$line"
    if [ "$spec_state" = present ]; then
      if spec_has "$method" "$path"; then mark=OK; else mark=MISSING; bad=$((bad + 1)); fi
    else
      mark="n/a"
    fi
    printf '%-3s %-15s %-7s %-47s %-7s %-8s %s\n' "$n" "$step" "$method" "$path" "$auth" "$mark" "$purpose"
  done
  echo
  echo "$n planned calls. Writes (real run only): customer display name, one address (removed again), cart, one COD order."
  if [ "$spec_state" = missing ]; then
    echo "WARNING: OpenAPI file not found; routes were not cross-checked."
  fi
  return "$bad"
}

if [ "$DRY_RUN" = 1 ]; then
  echo "== uat-e2e-cod --dry-run (no network call is made) =="
  if print_plan; then
    echo "DRY RUN OK: every planned route is declared in the OpenAPI document."
    exit 0
  fi
  echo "DRY RUN FAILED: at least one planned route is MISSING from the OpenAPI document." >&2
  exit 1
fi

# --- HTTP plumbing ----------------------------------------------------------------------------------------------------
TMP="$(mktemp -d)"
chmod 700 "$TMP"
trap 'rm -rf "$TMP"' EXIT
trap 'exit 130' INT TERM
BODY="$TMP/body"
HDRS="$TMP/hdrs"
REQH="$TMP/reqh"
REQB="$TMP/reqb"
STATUS=000
INSTALL_ID="uat-e2e-script"

# api METHOD PATH AUTH(0|1) [JSON-BODY [EXTRA-HEADER...]]
# Sets STATUS, fills $BODY and $HDRS. The token travels in a 0600 header FILE (curl -H @file), never on a command line.
# Redirects are never followed.
api() {
  local method="$1" path="$2" auth="$3" body="${4:-}" h
  local extra=()
  if [ "$#" -gt 4 ]; then extra=("${@:5}"); fi
  local curl_body=()
  {
    printf 'Accept: application/json\n'
    printf 'X-Tazzzo-Installation-Id: %s\n' "$INSTALL_ID"
    if [ -n "$body" ]; then printf 'Content-Type: application/json\n'; fi
    if [ "$auth" = 1 ]; then printf 'Authorization: Bearer %s\n' "$ACCESS_TOKEN"; fi
    for h in ${extra[@]+"${extra[@]}"}; do printf '%s\n' "$h"; done
  } >"$REQH"
  if [ -n "$body" ]; then
    printf '%s' "$body" >"$REQB"
    curl_body=(--data-binary "@$REQB")
  fi
  : >"$BODY"
  STATUS="$(curl -q -sS --connect-timeout 5 --max-time 30 -X "$method" -o "$BODY" -D "$HDRS" -w '%{http_code}' \
    -H "@$REQH" ${curl_body[@]+"${curl_body[@]}"} "${BASE_URL}${path}" 2>"$TMP/curlerr")" || STATUS=000
  rm -f "$REQH" "$REQB"
}

jv() { jq -r "$1" "$BODY" 2>/dev/null; } # value, or empty/null
hdr() { grep -i "^$1:" "$HDRS" 2>/dev/null | tail -n 1 | cut -d: -f2- | tr -d '\r' | sed 's/^[[:space:]]*//' || true; }
err_code() { # the flat error envelope's code, restricted to a safe alphabet
  local c
  c="$(jq -r '(.code // empty) | tostring | .[0:48]' "$BODY" 2>/dev/null | tr -c 'A-Za-z0-9_\n' '?')"
  printf '%s' "${c:-none}"
}
rand_key() { printf 'uat%s' "$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')"; }

# want NAME STATUS : record FAIL (and return 1) unless the last response had that status
want() {
  if [ "$STATUS" = "$2" ]; then return 0; fi
  if [ "$STATUS" = 000 ]; then
    fail "$1" "no HTTP answer (connection/TLS/timeout)"
  else
    fail "$1" "HTTP $STATUS code=$(err_code), expected $2"
  fi
}

# --- the steps --------------------------------------------------------------------------------------------------------
SKU="" ADDRESS_ID="" QUOTE_ID="" ORDER_ID="" CART_VERSION="" UNIT_PRICE="" OTP_CHALLENGE=""
B_STATE="" B_LOW="" B_MAX="" A_STATE="" A_LOW="" A_MAX=""
S_STATE="" S_LOW="" S_MAX="" S_PRICE="" S_BUYABLE=""
LOGGED_OUT=0

s_health() {
  api GET /health/live 0
  want "health live" 200 || return 1
  [ "$(jv '.status')" = UP ] || { fail "health live" "HTTP 200 but status is not UP"; return 1; }
  pass "health live" "200 UP"
  api GET /health/ready 0
  want "health ready" 200 || return 1
  [ "$(jv '.status')" = UP ] || { fail "health ready" "HTTP 200 but status is not UP"; return 1; }
  pass "health ready" "200 UP"
}

s_anonymous() {
  api GET /v1/customer/profile 0
  if [ "$STATUS" = 401 ]; then
    pass "anonymous refused" "401 without a token"
  else
    fail "anonymous refused" "HTTP $STATUS, expected 401"
  fi
}

s_serviceability() {
  api GET "/v1/serviceability?pin=$PIN" 0
  want "serviceability" 200 || return 1
  if [ "$(jv '.serviceable')" = true ]; then
    pass "serviceability" "PIN $PIN serviceable"
  else
    fail "serviceability" "PIN $PIN is NOT serviceable: seed a service area for it (UAT_PIN)"
  fi
}

# pick_buyable: the skuId of the first buyable, in-stock product of the last /products response (empty if none)
pick_buyable() {
  jq -r --argjson q "$QTY" '[.items[]? | select(.buyable == true and .stockState != "OUT_OF_STOCK" and (.maxOrderQuantity // 0) >= $q and .sellingPricePaise != null)][0].skuId // empty' "$BODY" 2>/dev/null
}

s_browse() {
  if [ -n "$SKU_PRESET" ]; then
    SKU="$SKU_PRESET"
    pass "browse" "using UAT_SKU_ID $SKU"
    return 0
  fi
  api GET /v1/categories 0
  want "browse roots" 200 || return 1
  local queue visited=0 node found="" kids
  queue="$(jv '[.items[]?.id] | join(" ")')"
  if [ -z "$queue" ]; then
    fail "browse roots" "200 but no root categories (catalogue not published/seeded)"
    return 1
  fi
  pass "browse roots" "$(jv '.items | length') root categories"
  while [ -n "$queue" ] && [ "$visited" -lt 60 ] && [ -z "$found" ]; do
    node="${queue%% *}"
    if [ "$node" = "$queue" ]; then queue=""; else queue="${queue#* }"; fi
    visited=$((visited + 1))
    api GET "/v1/categories/$node/products?pin=$PIN&page_size=24" 0
    if [ "$STATUS" = 200 ]; then
      found="$(pick_buyable)"
      if [ -n "$found" ]; then break; fi
    elif [ "$STATUS" != 400 ] && [ "$STATUS" != 404 ]; then
      want "browse products" 200 || return 1
    fi
    api GET "/v1/categories/$node/children" 0
    if [ "$STATUS" = 200 ]; then
      kids="$(jv '[.items[]?.id] | join(" ")')"
      if [ -n "$kids" ]; then queue="${queue:+$queue }$kids"; fi
    fi
  done
  if [ -z "$found" ]; then
    fail "browse products" "no buyable in-stock product (qty $QTY) found in $visited categories for PIN $PIN: seed products/stock/price"
    return 1
  fi
  SKU="$found"
  pass "browse products" "buyable product $SKU found after $visited categories"
}

stock_snapshot() { # sets S_*; returns 1 on failure (already recorded)
  api GET "/v1/products/$SKU?pin=$PIN" 0
  want "$1" 200 || return 1
  S_STATE="$(jv '.stockState')"
  S_LOW="$(jv '.lowStockRemaining // empty')"
  S_MAX="$(jv '.maxOrderQuantity // 0')"
  S_PRICE="$(jv '.sellingPricePaise // empty')"
  S_BUYABLE="$(jv '.buyable')"
}

s_product() {
  stock_snapshot "product detail" || return 1
  if [ "$S_BUYABLE" != true ] || [ -z "$S_PRICE" ]; then
    fail "product detail" "$SKU is not buyable or has no price for PIN $PIN"
    return 1
  fi
  B_STATE="$S_STATE" B_LOW="$S_LOW" B_MAX="$S_MAX" UNIT_PRICE="$S_PRICE"
  pass "product detail" "$SKU price ${S_PRICE}p stock=$S_STATE maxOrder=$S_MAX${S_LOW:+ lowStockRemaining=$S_LOW}"
}

s_otp_request() {
  api POST /v1/auth/otp/request 0 "$(jq -nc --arg p "$PHONE" '{phone:$p}')"
  want "otp request" 202 || return 1
  OTP_CHALLENGE="$(jv '.challengeId')"
  if ! [[ $OTP_CHALLENGE =~ ^OTP_[A-Za-z0-9_-]+$ ]]; then
    fail "otp request" "202 but no valid challengeId"
    return 1
  fi
  pass "otp request" "202 challenge created for $PHONE_MASKED (valid $(jv '.expiresInSeconds')s)"
}

s_otp_verify() {
  if [ -n "$OTP_PRESET" ]; then
    OTP="$OTP_PRESET"
  elif [ -t 0 ]; then
    printf 'Enter the 6-digit OTP for %s (input hidden): ' "$PHONE_MASKED" >&2
    IFS= read -rs OTP
    printf '\n' >&2
  else
    fail "otp verify" "no OTP: set UAT_OTP or run from a terminal (the backend never logs the code in LOGGING mode)"
    return 1
  fi
  if ! [[ $OTP =~ $OTP_RE ]]; then
    OTP=""
    fail "otp verify" "the OTP must be exactly 6 digits"
    return 1
  fi
  api POST /v1/auth/otp/verify 0 "$(jq -nc --arg c "$OTP_CHALLENGE" --arg o "$OTP" '{challengeId:$c,otp:$o}')"
  OTP=""
  want "otp verify" 200 || return 1
  GRANT_ID="$(jv '.grantId')"
  if [ "$(jv '.verified')" != true ] || ! [[ $GRANT_ID =~ ^GRANT_[A-Za-z0-9_-]+$ ]]; then
    GRANT_ID=""
    fail "otp verify" "200 but verified!=true or no grant"
    return 1
  fi
  pass "otp verify" "200 verified, one-time grant issued"
}

s_session() {
  api POST /v1/auth/session 0 "$(jq -nc --arg g "$GRANT_ID" '{grantId:$g}')"
  want "session" 200 || return 1
  ACCESS_TOKEN="$(jv '.accessToken')"
  REFRESH_TOKEN="$(jv '.refreshToken')"
  GRANT_ID=""
  if [ -z "$ACCESS_TOKEN" ] || [ "$ACCESS_TOKEN" = null ] || [ -z "$REFRESH_TOKEN" ] || [ "$REFRESH_TOKEN" = null ]; then
    ACCESS_TOKEN="" REFRESH_TOKEN=""
    fail "session" "200 but tokens are missing"
    return 1
  fi
  pass "session" "200 access + refresh tokens issued (access valid $(jv '.accessTokenExpiresIn')s)"
}

s_profile() {
  api GET /v1/customer/profile 1
  want "profile read" 200 || return 1
  local ver etag
  ver="$(jv '.version')"
  etag="$(hdr etag)"
  if [ -z "$etag" ]; then etag="\"profile-$ver\""; fi
  pass "profile read" "200 version $ver"
  api PATCH /v1/customer/profile 1 '{"displayName":"UAT E2E Tester"}' "If-Match: $etag"
  want "profile update" 200 || return 1
  if [ "$(jv '.displayName')" != "UAT E2E Tester" ]; then
    fail "profile update" "200 but displayName was not applied"
    return 1
  fi
  pass "profile update" "200 displayName applied, version $(jv '.version')"
}

s_address() {
  local body
  body="$(jq -nc --arg pin "$PIN" --arg ph "$PHONE" '{label:"HOME",recipientName:"UAT E2E Tester",recipientPhone:$ph,addressLine1:"1 UAT Test Lane",city:"Bengaluru",state:"Karnataka",postalCode:$pin}')"
  api POST /v1/customer/addresses 1 "$body" "Idempotency-Key: $(rand_key)"
  want "address create" 201 || return 1
  ADDRESS_ID="$(jv '.addressId')"
  if ! [[ $ADDRESS_ID =~ ^ADDR_[A-Za-z0-9_-]+$ ]]; then
    ADDRESS_ID=""
    fail "address create" "201 but no addressId"
    return 1
  fi
  pass "address create" "201 $ADDRESS_ID serviceable=$(jv '.serviceability.serviceable')"
  api GET /v1/customer/addresses 1
  want "address list" 200 || return 1
  if [ "$(jq -r --arg id "$ADDRESS_ID" '[.items[]? | select(.addressId == $id)] | length' "$BODY" 2>/dev/null)" = 1 ]; then
    pass "address list" "200 the new address is listed"
  else
    fail "address list" "200 but the new address is not listed"
    return 1
  fi
  api PUT "/v1/customer/addresses/$ADDRESS_ID/default" 1
  want "address default" 200 || return 1
  if [ "$(jv '.isDefault')" != true ]; then
    fail "address default" "200 but isDefault is not true"
    return 1
  fi
  pass "address default" "200 now the default address"
}

s_cart() {
  api GET /v1/customer/cart 1
  want "cart read" 200 || return 1
  CART_VERSION="$(jv '.version')"
  if [ "$(jv '.items | length')" != 0 ]; then
    api DELETE /v1/customer/cart 1 "" "If-Match: \"cart-$CART_VERSION\""
    want "cart clear leftover" 200 || return 1
    CART_VERSION="$(jv '.version')"
    pass "cart clear leftover" "200 emptied a cart left by an earlier run"
  fi
  api PUT "/v1/customer/cart/items/$SKU" 1 "$(jq -nc --argjson q "$QTY" '{quantity:$q}')" "If-Match: \"cart-$CART_VERSION\""
  want "cart set" 200 || return 1
  CART_VERSION="$(jv '.version')"
  local line
  line="$(jq -r --arg s "$SKU" '[.items[]? | select(.skuId == $s)][0] | "\(.quantity)|\(.buyable)"' "$BODY" 2>/dev/null)"
  if [ "$line" != "$QTY|true" ]; then
    fail "cart set" "200 but the line is '$line', expected '$QTY|true' (issues: $(jq -r '[.items[]?.issues[]?] | join(",")' "$BODY" 2>/dev/null))"
    return 1
  fi
  pass "cart set" "200 $SKU x$QTY buyable, subtotal $(jv '.subtotalPaise')p, version $CART_VERSION"
}

s_quote() {
  api POST /v1/customer/checkout/quote 1 "$(jq -nc --arg a "$ADDRESS_ID" '{addressId:$a}')" "If-Match: \"cart-$CART_VERSION\"" "Idempotency-Key: $(rand_key)"
  want "checkout quote" 200 || return 1
  QUOTE_ID="$(jv '.quoteId')"
  if ! [[ $QUOTE_ID =~ ^CHKQ_[A-Za-z0-9_-]+$ ]]; then
    fail "checkout quote" "200 but no quoteId"
    return 1
  fi
  local sub expect
  sub="$(jv '.subtotalPaise')"
  expect=$((UNIT_PRICE * QTY))
  if [ "$sub" != "$expect" ]; then
    fail "checkout quote" "subtotal ${sub}p differs from unit price ${UNIT_PRICE}p x $QTY"
    return 1
  fi
  pass "checkout quote" "200 $QUOTE_ID subtotal ${sub}p payable $(jv '.moneyPreview.payablePaise // "n/a"')p"
}

s_order() {
  api POST /v1/customer/orders 1 "$(jq -nc --arg q "$QUOTE_ID" '{quoteId:$q,paymentMethod:"COD"}')"
  want "COD order" 200 || return 1
  ORDER_ID="$(jv '.orderId')"
  local status cond line
  status="$(jv '.status')"
  cond="$(jv '.paymentCondition')"
  line="$(jq -r --arg s "$SKU" '[.items[]? | select(.skuId == $s)][0].quantity // empty' "$BODY" 2>/dev/null)"
  if ! [[ $ORDER_ID =~ ^ORD_[A-Za-z0-9_-]+$ ]]; then
    ORDER_ID=""
    fail "COD order" "200 but no orderId"
    return 1
  fi
  if [ "$status" != CONFIRMED ] || [ "$cond" != COD_DUE ] || [ "$line" != "$QTY" ]; then
    fail "COD order" "status=$status paymentCondition=$cond line quantity='$line' (expected CONFIRMED / COD_DUE / $QTY)"
    return 1
  fi
  pass "COD order" "200 $ORDER_ID CONFIRMED COD_DUE payable $(jv '.money.payablePaise // .subtotalPaise')p"
  # Replay of the same quote: the same order again, never a second reservation.
  api POST /v1/customer/orders 1 "$(jq -nc --arg q "$QUOTE_ID" '{quoteId:$q,paymentMethod:"COD"}')"
  want "order replay" 200 || return 1
  if [ "$(jv '.orderId')" = "$ORDER_ID" ]; then
    pass "order replay" "200 same order id (idempotent by quoteId)"
  else
    fail "order replay" "the replay returned a DIFFERENT order id: a second order exists"
    return 1
  fi
}

s_orders() {
  api GET "/v1/customer/orders?page_size=50" 1
  want "order history" 200 || return 1
  local n
  n="$(jq -r --arg id "$ORDER_ID" '[.items[]? | select(.orderId == $id)] | length' "$BODY" 2>/dev/null)"
  if [ "$n" = 1 ]; then
    pass "order history" "200 the order is listed exactly once"
  else
    fail "order history" "the order appears ${n:-?} times in the list (expected 1)"
    return 1
  fi
  api GET "/v1/customer/orders/$ORDER_ID" 1
  want "order detail" 200 || return 1
  if [ "$(jv '.status')" != CONFIRMED ]; then
    fail "order detail" "status is not CONFIRMED"
    return 1
  fi
  pass "order detail" "200 CONFIRMED"
}

# avail_of STATE LOW MAX : the available-units figure as seen through the public API
avail_of() {
  case "$1" in
    LOW_STOCK) if [ -n "$2" ]; then printf '%s' "$2"; return; fi ;;
    OUT_OF_STOCK) printf '0'; return ;;
  esac
  printf '%s' "$3"
}

s_stock() {
  stock_snapshot "stock after" || return 1
  A_STATE="$S_STATE" A_LOW="$S_LOW" A_MAX="$S_MAX"
  local before after delta
  before="$(avail_of "$B_STATE" "$B_LOW" "$B_MAX")"
  after="$(avail_of "$A_STATE" "$A_LOW" "$A_MAX")"
  delta=$((before - after))
  if [ "$B_STATE" = LOW_STOCK ] && [ -n "$B_LOW" ]; then
    # LOW_STOCK states the exact remaining units on the 'before' side
    if [ "$after" = $((before - QTY)) ]; then
      pass "stock moved once" "exact: $before -> $after units (-$QTY)"
    else
      fail "stock moved once" "exact: $before -> $after units, expected $((before - QTY))"
      return 1
    fi
  elif [ "$delta" = "$QTY" ]; then
    pass "stock moved once" "observed $before -> $after (-$QTY)"
  elif [ "$delta" != 0 ]; then
    fail "stock moved once" "observed $before -> $after (-$delta), expected -$QTY"
    return 1
  else
    local msg="maxOrderQuantity is $before before and after (state $B_STATE, capped): the public API cannot show the movement; confirm it in the CMS inventory screen"
    if [ "$REQUIRE_STOCK_PROOF" = 1 ]; then
      fail "stock moved once" "$msg (UAT_REQUIRE_STOCK_PROOF=1)"
      return 1
    fi
    skip "stock moved once" "$msg"
  fi
  # The idempotent replay above must not have moved it again: compare with a second read.
  stock_snapshot "stock stable" || return 1
  if [ "$(avail_of "$S_STATE" "$S_LOW" "$S_MAX")" = "$after" ]; then
    pass "stock stable" "unchanged by the idempotent replay"
  else
    fail "stock stable" "the stock changed again after the replay"
    return 1
  fi
}

s_cart_cleared() {
  api GET /v1/customer/cart 1
  want "cart cleared" 200 || return 1
  if [ "$(jv '.items | length')" = 0 ]; then
    pass "cart cleared" "200 the cart is empty after the order"
  else
    fail "cart cleared" "the cart still holds $(jv '.items | length') line(s)"
    return 1
  fi
}

s_refresh() {
  local old_refresh="$REFRESH_TOKEN" new_access new_refresh
  api POST /v1/auth/refresh 0 "$(jq -nc --arg r "$old_refresh" '{refreshToken:$r}')"
  want "token rotation" 200 || return 1
  new_access="$(jv '.accessToken')"
  new_refresh="$(jv '.refreshToken')"
  if [ -z "$new_access" ] || [ "$new_access" = null ] || [ -z "$new_refresh" ] || [ "$new_refresh" = null ]; then
    fail "token rotation" "200 but tokens are missing"
    return 1
  fi
  if [ "$new_refresh" = "$old_refresh" ]; then
    fail "token rotation" "the refresh token was NOT rotated"
    return 1
  fi
  ACCESS_TOKEN="$new_access"
  REFRESH_TOKEN="$new_refresh"
  pass "token rotation" "200 new access + new refresh token (the refresh token changed)"
  api GET /v1/customer/profile 1
  want "rotated token works" 200 || return 1
  pass "rotated token works" "200 with the new access token"
  api POST /v1/auth/refresh 0 "$(jq -nc --arg r "$old_refresh" '{refreshToken:$r}')"
  if [ "$STATUS" = 401 ]; then
    pass "old refresh refused" "401 the rotated-away refresh token is refused"
  else
    fail "old refresh refused" "HTTP $STATUS, expected 401: the old refresh token still works"
    return 1
  fi
}

# Clean-up never blocks the logout checks; a failure is still recorded (and fails the run).
s_cleanup() {
  if [ -z "$ADDRESS_ID" ]; then return 0; fi
  local etag
  api GET "/v1/customer/addresses/$ADDRESS_ID" 1
  etag="$(hdr etag)"
  if [ -z "$etag" ]; then etag="\"address-$(jv '.version')\""; fi
  api DELETE "/v1/customer/addresses/$ADDRESS_ID" 1 "" "If-Match: $etag"
  if [ "$STATUS" = 204 ]; then
    pass "address cleanup" "204 the E2E address is removed (the order keeps its own snapshot)"
    ADDRESS_ID=""
  else
    fail "address cleanup" "HTTP $STATUS code=$(err_code), expected 204" || true
  fi
  return 0
}

s_logout() {
  api POST /v1/auth/logout 1
  want "logout" 204 || return 1
  LOGGED_OUT=1
  pass "logout" "204 session revoked"
  api GET /v1/customer/profile 1
  if [ "$STATUS" = 401 ]; then
    pass "revoked access refused" "401 after logout"
  else
    fail "revoked access refused" "HTTP $STATUS, expected 401: the access token still works after logout"
    return 1
  fi
  api POST /v1/auth/refresh 0 "$(jq -nc --arg r "$REFRESH_TOKEN" '{refreshToken:$r}')"
  if [ "$STATUS" = 401 ]; then
    pass "revoked refresh refused" "401 after logout"
  else
    fail "revoked refresh refused" "HTTP $STATUS, expected 401: the refresh token still works after logout"
    return 1
  fi
}

ABORTED=0
step() { # <function> <row name used if the step is skipped>
  if [ "$ABORTED" = 1 ]; then
    skip "$2" "not run: an earlier step failed"
    return 0
  fi
  "$1" || ABORTED=1
}

# --- run --------------------------------------------------------------------------------------------------------------
echo "== uat-e2e-cod against $TARGET_HOST (writes: customer $PHONE_MASKED, one address, cart, one COD order) =="
step s_health "health"
step s_anonymous "anonymous refused"
step s_serviceability "serviceability"
step s_browse "browse"
step s_product "product detail"
step s_otp_request "otp request"
step s_otp_verify "otp verify"
step s_session "session"
step s_profile "profile"
step s_address "address"
step s_cart "cart"
step s_quote "checkout quote"
step s_order "COD order"
step s_orders "order history"
step s_stock "stock moved once"
step s_cart_cleared "cart cleared"
step s_refresh "token rotation"
step s_cleanup "address cleanup"
step s_logout "logout"

# After a failure with a live session, revoke it anyway (best effort; reported, not part of the verdict).
if [ "$ABORTED" = 1 ] && [ -n "$ACCESS_TOKEN" ] && [ "$LOGGED_OUT" = 0 ]; then
  api POST /v1/auth/logout 1
  if [ "$STATUS" = 204 ]; then
    record "cleanup logout" INFO "session revoked after the failure"
  else
    record "cleanup logout" INFO "could not revoke the session (HTTP $STATUS)"
  fi
fi

# --- report -----------------------------------------------------------------------------------------------------------
PASS_N=0 FAIL_N=0 SKIP_N=0
i=0
printf '\n%-26s %-6s %s\n' "CHECK" "RESULT" "DETAIL"
printf '%-26s %-6s %s\n' "--------------------------" "------" "------"
while [ "$i" -lt "${#NAMES[@]}" ]; do
  case "${RESULTS[$i]}" in
    PASS) PASS_N=$((PASS_N + 1)) ;;
    FAIL) FAIL_N=$((FAIL_N + 1)) ;;
    SKIP) SKIP_N=$((SKIP_N + 1)) ;;
  esac
  printf '%-26s %-6s %s\n' "$(scrub "${NAMES[$i]}")" "${RESULTS[$i]}" "$(scrub "${DETAILS[$i]}")"
  i=$((i + 1))
done
printf '\nSUMMARY: %d PASS, %d FAIL, %d SKIP  (target %s)\n' "$PASS_N" "$FAIL_N" "$SKIP_N" "$TARGET_HOST"

if [ -n "$REPORT_FILE" ]; then
  {
    printf '### UAT customer COD E2E (%s)\n\n| Check | Result | Detail |\n|---|---|---|\n' "$TARGET_HOST"
    i=0
    while [ "$i" -lt "${#NAMES[@]}" ]; do
      printf '| %s | %s | %s |\n' "$(scrub "${NAMES[$i]}")" "${RESULTS[$i]}" "$(scrub "${DETAILS[$i]}" | tr '|' '/')"
      i=$((i + 1))
    done
    printf '\n**%d PASS, %d FAIL, %d SKIP**\n' "$PASS_N" "$FAIL_N" "$SKIP_N"
  } >>"$REPORT_FILE"
fi

if [ "$FAIL_N" -gt 0 ]; then
  echo "RESULT: FAIL" >&2
  exit 1
fi
echo "RESULT: PASS"
exit 0
