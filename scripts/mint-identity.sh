#!/usr/bin/env bash
# Prints a signed X-SafeLedger-Identity header value for local testing with the MCP Inspector or
# curl. Same format as IdentityToken.sign: base64url("v1|customer|session|issuedAt") "." base64url(HMAC).
# Valid for 5 minutes.
#
# Usage: IDENTITY_HMAC_SECRET=... scripts/mint-identity.sh <customerId> [sessionId]
#    or: scripts/mint-identity.sh <customerId> [sessionId]   (reads infra/.env)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ -z "${IDENTITY_HMAC_SECRET:-}" && -f infra/.env ]]; then
  IDENTITY_HMAC_SECRET="$(grep -E '^IDENTITY_HMAC_SECRET=' infra/.env | cut -d= -f2-)"
fi
: "${IDENTITY_HMAC_SECRET:?Set IDENTITY_HMAC_SECRET or create infra/.env}"
customer="${1:?customerId required, e.g. 11111111-1111-4111-8111-000000000001}"
session="${2:-local-$(date +%s)}"

b64url() { base64 | tr -d '\n=' | tr '+/' '-_'; }
payload="v1|${customer}|${session}|$(date +%s)"
signature="$(printf '%s' "$payload" | openssl dgst -sha256 -hmac "$IDENTITY_HMAC_SECRET" -binary | b64url)"
printf '%s.%s\n' "$(printf '%s' "$payload" | b64url)" "$signature"
