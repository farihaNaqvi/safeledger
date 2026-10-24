#!/usr/bin/env bash
# Live eval: resets the local stack to the seed data, runs the YAML cases against the configured
# model, writes eval/target/eval-report/, then stops the stack.
#   EVAL_CASES=g01,i11        run only cases whose id starts with one of these
#   EVAL_PRICE_INPUT/OUTPUT   USD per million tokens, for the cost estimate
#   EVAL_TURN_TIMEOUT_SECONDS seconds to wait for one chat reply (default 150)
#   EVAL_COMPOSE_OVERRIDE     extra compose file, e.g. a longer turn timeout for a local model
#   KEEP_STACK=1              leave the stack running afterwards
# With Bedrock or the Claude API every turn is billed. Check the case count before running.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

[ -f infra/.env ] || { echo "infra/.env is missing, see infra/.env.example" >&2; exit 1; }
set -a; . infra/.env; set +a

compose=(docker compose -f infra/docker-compose.yml)
[ -n "${EVAL_COMPOSE_OVERRIDE:-}" ] && compose+=(-f "$EVAL_COMPOSE_OVERRIDE")

provider="${SAFELEDGER_CHAT_MODEL:-bedrock-converse}"
case "$provider" in
  bedrock-converse) model="${BEDROCK_MODEL_ID:?set BEDROCK_MODEL_ID in infra/.env}" ;;
  anthropic)
    : "${ANTHROPIC_API_KEY:?set ANTHROPIC_API_KEY in infra/.env}"
    model="anthropic:${ANTHROPIC_MODEL:-claude-haiku-5-5}" ;;
  *) model="ollama:${OLLAMA_MODEL:-qwen2.5:7b}" ;;
esac

if [ "$provider" = "bedrock-converse" ] || [ "${BEDROCK_GUARDRAIL_ENABLED:-true}" = "true" ]; then
  [ -n "${AWS_ACCESS_KEY_ID:-}" ] ||
    eval "$(aws configure export-credentials --profile "${AWS_PROFILE:-safeledger}" --format env | tr -d '\r')"
fi

echo "Resetting the local stack to the seed data (synthetic data only)"
"${compose[@]}" down -v
"${compose[@]}" up --build --wait

status=0
EVAL_MODEL="$model" mvn --batch-mode --no-transfer-progress -pl eval verify -Deval.live=true || status=$?

[ "${KEEP_STACK:-0}" = "1" ] || "${compose[@]}" down
echo "Report: eval/target/eval-report/eval-report.md"
exit $status
