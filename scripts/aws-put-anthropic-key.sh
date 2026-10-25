#!/usr/bin/env bash
# Copies ANTHROPIC_API_KEY from infra/.env into Secrets Manager without printing it.
. "$(dirname "${BASH_SOURCE[0]}")/aws-common.sh"
NAME="${ANTHROPIC_SECRET_NAME:-safeledger/anthropic-api-key}"

key="$(grep '^ANTHROPIC_API_KEY=' infra/.env | cut -d= -f2- | tr -d '\r')"
[ -n "$key" ] || { echo "ANTHROPIC_API_KEY is empty in infra/.env" >&2; exit 1; }
tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
printf '%s' "$key" > "$tmp"

if "$AWS" secretsmanager describe-secret --secret-id "$NAME" >/dev/null 2>&1; then
  "$AWS" secretsmanager put-secret-value --secret-id "$NAME" --secret-string "file://$tmp" >/dev/null
else
  "$AWS" secretsmanager create-secret --name "$NAME" --tags Key=Project,Value=SafeLedger \
    --description "Claude API key for SafeLedger agent-service" --secret-string "file://$tmp" >/dev/null
fi
echo "Stored the key in $NAME ($AWS_REGION)"
