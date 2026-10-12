#!/usr/bin/env bash
# Drift guard: every environment variable that application.yml reads (${NAME:default}) must be named in
# docs/ops/UAT_ENVIRONMENT_CONTRACT.md. Static, no network. Exit 0 = documented, 1 = at least one missing.
# Usage: scripts/uat-env-contract-check.sh
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
YML=services/catalog-service/src/main/resources/application.yml
DOC=docs/ops/UAT_ENVIRONMENT_CONTRACT.md
[ -f "$YML" ] && [ -f "$DOC" ] || { echo "env-contract-check: $YML or $DOC not found" >&2; exit 2; }

missing=0
total=0
while IFS= read -r name; do
  total=$((total + 1))
  if ! grep -qF -- "$name" "$DOC"; then
    echo "env-contract-check: $name is read by application.yml but not named in $DOC" >&2
    missing=$((missing + 1))
  fi
done < <(grep -oE '[$][{][A-Z][A-Z0-9_]*:' "$YML" | sed -E 's/^..//; s/:$//' | sort -u)

if [ "$missing" -gt 0 ]; then
  echo "env-contract-check: $missing of $total variable(s) undocumented" >&2
  exit 1
fi
echo "env-contract-check: all $total environment variables of application.yml are documented"
