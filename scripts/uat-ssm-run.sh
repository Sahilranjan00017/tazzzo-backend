#!/usr/bin/env bash
# Runs the UAT deploy/rollback document on the tagged UAT backend instance(s) through SSM Run Command (no SSH) and waits
# for a terminal state. Needs AWS credentials in the environment (the workflow assumes the OIDC role first).
#
#   scripts/uat-ssm-run.sh <deploy|rollback> <40-hex-git-sha>
#
# Env: AWS_REGION (required), UAT_EC2_ROLE_TAG (default backend), UAT_SSM_TIMEOUT_SECONDS (default 600).
# Prints only the command id, per-instance status and sanitized output (secret-looking lines are redacted). It never
# prints the parameter values. Writes command_id=... to $GITHUB_OUTPUT when set. Exit 0 only when every invocation is Success.
set -euo pipefail

ACTION="${1:-}"
GIT_SHA="${2:-}"
case "$ACTION" in deploy | rollback) ;; *) echo "action must be deploy or rollback" >&2; exit 2 ;; esac
[[ "$GIT_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo "git sha must be 40 lowercase hex characters" >&2; exit 2; }
: "${AWS_REGION:?AWS_REGION is required}"
ROLE_TAG="${UAT_EC2_ROLE_TAG:-backend}"
TIMEOUT="${UAT_SSM_TIMEOUT_SECONDS:-600}"
DOCUMENT="tazzzo-uat-deploy"

sanitize() { # redact anything that looks like a credential or a connection string, keep the rest
  grep -viE 'secret|token|password|passwd|authorization|api[_-]?key|access[_-]?key|_b64|mongodb(\+srv)?://|rediss?://' |
    tail -n 40 | cut -c1-300 || true
}

COMMAND_ID="$(aws ssm send-command \
  --region "$AWS_REGION" \
  --document-name "$DOCUMENT" \
  --targets "Key=tag:Project,Values=Tazzzo" "Key=tag:Environment,Values=uat" "Key=tag:Role,Values=${ROLE_TAG}" \
  --parameters "GitSha=${GIT_SHA},Action=${ACTION}" \
  --comment "tazzzo uat ${ACTION} via GitHub Actions" \
  --timeout-seconds 300 \
  --max-concurrency 1 --max-errors 0 \
  --query 'Command.CommandId' --output text)"
echo "ssm: document=${DOCUMENT} action=${ACTION} command_id=${COMMAND_ID}"
[ -z "${GITHUB_OUTPUT:-}" ] || echo "command_id=${COMMAND_ID}" >>"$GITHUB_OUTPUT"

deadline=$((SECONDS + TIMEOUT))
statuses=""
while :; do
  statuses="$(aws ssm list-command-invocations --region "$AWS_REGION" --command-id "$COMMAND_ID" \
    --query 'CommandInvocations[].Status' --output text 2>/dev/null || true)"
  if [ -n "$statuses" ]; then
    pending=0
    for s in $statuses; do
      case "$s" in Success | Failed | Cancelled | TimedOut | Cancelling) ;; *) pending=1 ;; esac
    done
    echo "ssm: statuses=${statuses//$'\t'/,}"
    [ "$pending" -eq 0 ] && break
  else
    echo "ssm: no invocation yet (no instance matched the tags, or still registering)"
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "ssm: TIMEOUT after ${TIMEOUT}s waiting for command ${COMMAND_ID}" >&2
    [ -n "$statuses" ] || echo "ssm: no instance ever received the command: check tags Project=Tazzzo Environment=uat Role=${ROLE_TAG} and that the instance is SSM-managed (online)." >&2
    exit 1
  fi
  sleep 10
done

rc=0
for iid in $(aws ssm list-command-invocations --region "$AWS_REGION" --command-id "$COMMAND_ID" \
  --query 'CommandInvocations[].InstanceId' --output text); do
  status="$(aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$COMMAND_ID" --instance-id "$iid" \
    --query 'Status' --output text)"
  echo "ssm: instance=${iid} status=${status}"
  echo "--- sanitized stdout (last lines) ---"
  aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$COMMAND_ID" --instance-id "$iid" \
    --query 'StandardOutputContent' --output text | sanitize
  echo "--- sanitized stderr (last lines) ---"
  aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$COMMAND_ID" --instance-id "$iid" \
    --query 'StandardErrorContent' --output text | sanitize
  [ "$status" = Success ] || rc=1
done
exit "$rc"
