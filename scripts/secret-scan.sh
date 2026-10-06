#!/usr/bin/env bash
# Secret scan over every tracked file: no third-party action, no network. Fails (exit 1) on any finding not covered by
# scripts/secret-scan.allow (extended regexes matched against "path:line:text", each with a reason comment).
# It prints the file and line number only, never the matched text, so a real secret is not copied into CI logs.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

PATTERNS=(
  'AKIA[0-9A-Z]{16}'                                                     # AWS access key id
  '-----BEGIN ([A-Z]+ )?PRIVATE KEY-----'                                # PEM private key
  'mongodb(\+srv)?://[^/[:space:]:@"'\''<>]+:[^@[:space:]"'\''<>]+@'     # Mongo URI with an inline password
  'rediss?://[^/[:space:]@"'\''<>]*:[^@[:space:]"'\''<>]+@'              # Redis URI with an inline password
  'AIza[0-9A-Za-z_-]{35}'                                                # Google API key
  'gh[pousr]_[A-Za-z0-9]{36,}'                                           # GitHub token
  'xox[abprs]-[A-Za-z0-9-]{10,}'                                         # Slack token
  'sk_live_[0-9A-Za-z]{16,}'                                             # payment provider live secret key
  'rzp_live_[0-9A-Za-z]{10,}'                                            # Razorpay live key id
)

ALLOW=scripts/secret-scan.allow
rules=$(mktemp); hits=$(mktemp); trap 'rm -f "$rules" "$hits"' EXIT
[ -f "$ALLOW" ] && grep -vE '^[[:space:]]*(#|$)' "$ALLOW" > "$rules" || true

for p in "${PATTERNS[@]}"; do
  # -I skips binaries; tracked files only (the index), including files staged for the next commit
  git grep -nIE -e "$p" -- . ':!scripts/secret-scan.sh' >> "$hits" || true
done
if [ -s "$rules" ]; then
  findings=$(grep -vEf "$rules" "$hits" || true)
else
  findings=$(cat "$hits")
fi
if [ -n "$findings" ]; then
  printf '%s\n' "$findings" | cut -d: -f1,2 | sort -u | sed 's/^/secret-scan: possible secret in /'
  echo "secret-scan: $(printf '%s\n' "$findings" | cut -d: -f1,2 | sort -u | wc -l | tr -d ' ') finding(s). Remove the secret (and rotate it), or allowlist a documented placeholder with a reason."
  exit 1
fi
echo "secret-scan: clean ($(git ls-files | wc -l | tr -d ' ') tracked files)"
