# SafeLedger

A guarded conversational banking assistant for a fictional bank, built on MCP, Spring AI and Amazon Bedrock. The point of the project is the engineering around the agent — confirmation outside the model's reach, idempotent and audited writes, identity from the session rather than tool arguments, and prompt-injection detection with automated tests — not the chat itself.

All data in this repo is synthetic. No real card numbers, names or employer data.

## Status

Building phase by phase. Currently: **Phase 4 — Agent service**.

| Phase | Status |
|---|---|
| 0. Manual setup | done outside this repo |
| 1. Project skeleton | done |
| 2. Mock bank API | done |
| 3. MCP server and policy layer | done |
| 4. Agent service | in progress |
| 5. Evaluation and injection test suite | not started |
| 6. Observability and cost | not started |
| 7. AWS deployment with CDK | not started |
| 8. Documentation and demo | not started |

## Architecture

Three Spring Boot services plus Postgres, in one Maven multi-module repo:

1. `mock-bank-api` (port 8081) — plain REST API owning the bank data. Knows nothing about AI.
2. `bank-mcp-server` (port 8082) — Spring AI MCP server wrapping the bank API as tools, enforcing the policy layer (tool tiers, pending actions, limits, idempotency, audit).
3. `agent-service` (port 8080) — customer-facing API and chat UI. Spring AI `ChatClient` over Bedrock Converse, MCP client, Bedrock Guardrails, session + confirmation flow.
4. `eval` — golden conversations and the prompt-injection test suite.
5. `infra` — Docker Compose for local, AWS CDK (Java) for cloud.

## Quick start (local)

Requires JDK 25 (or the latest LTS your Maven toolchain resolves to), Maven, and Docker Desktop.

First, create `infra/.env` from [`infra/.env.example`](infra/.env.example) and set
`IDENTITY_HMAC_SECRET` (at least 32 characters, e.g. `openssl rand -base64 48`). The file is
git-ignored; compose refuses to start without it.

```
mvn verify          # build + unit + integration tests, Spotless, JaCoCo
make up             # docker compose up: Postgres + all three services
make test           # run the test suite
make down           # tear down
```

Each service exposes `/actuator/health`:

- `http://localhost:8081/actuator/health` (mock-bank-api)
- `http://localhost:8082/actuator/health` (bank-mcp-server)
- `http://localhost:8080/actuator/health` (agent-service)

## Mock bank API (port 8081)

OpenAPI document: [`api/bank-api.yaml`](api/bank-api.yaml). Swagger UI runs at
`http://localhost:8081/swagger-ui.html` when the stack is up.

Every request needs an `X-Customer-Id` header (in the real flow, the MCP server sends it from the
signed session). Anything that belongs to another customer returns `404`, exactly like a resource
that does not exist.

| Method and path | Notes |
|---|---|
| `GET /customers/{id}/accounts` | |
| `GET /accounts/{id}/balance` | |
| `GET /customers/{id}/cards` | last four digits only |
| `POST /cards/{id}/freeze`, `POST /cards/{id}/unfreeze` | idempotent; blocked cards return `409` |
| `GET /accounts/{id}/transactions?from&to&merchant&minAmount&maxAmount&limit` | newest first, `limit` 1 to 200, default 50 |
| `GET /customers/{id}/payees` | masked account numbers only |
| `POST /transfers` | needs `Idempotency-Key`; `422` on insufficient funds or unknown payee |
| `POST /disputes`, `GET /customers/{id}/disputes` | needs `Idempotency-Key`; one open dispute per transaction |

Idempotency: repeating a POST with the same key and body returns the original result with
`200` and `Idempotent-Replayed: true`, and changes nothing. The same key with a different body
returns `422 IDEMPOTENCY_KEY_REUSED`. Errors are RFC 9457 problem documents with a `code` field.

Seed data is entirely synthetic: 3 customers, 5 accounts, 5 cards, 6 payees and 200 transactions
over the last 90 days. Six transactions and one payee name contain deliberate prompt injection
text, used to test the agent later.

After changing the API, regenerate the OpenAPI document and commit it:

```
mvn -pl mock-bank-api verify -Dopenapi.update=true
```

## MCP server (port 8082)

Spring AI MCP server over Streamable HTTP at `http://localhost:8082/mcp`. It wraps the bank API
as tools and enforces the policy layer. Design record:
[`adr/0001-pending-actions-outside-the-model.md`](adr/0001-pending-actions-outside-the-model.md).

| Tier | Tools |
|---|---|
| Read | `list_accounts`, `get_balance`, `list_cards`, `search_transactions` (max 50), `list_payees`, `list_disputes` |
| Write (pending only) | `request_card_freeze`, `request_card_unfreeze`, `request_dispute`, `request_transfer` |

**The model can never complete a write.** Write tools check the request against the bank and
create a `PENDING` action that expires after 5 minutes. The customer confirms it through
`POST /internal/actions/{id}/confirm`, an HTTP endpoint that is not an MCP tool. Confirm runs
exactly once (one conditional `PENDING → CONFIRMED` update) and calls the bank with the action's
stored idempotency key. `POST /internal/actions/{id}/cancel` and `GET /internal/actions` complete
the set.

**Identity comes from a signed header, never from tool arguments.** Every request to `/mcp` and
`/internal/**` needs `X-SafeLedger-Identity`: an HMAC-SHA256 token over customer ID, session ID
and issue time, valid for 5 minutes. Anything else gets `401` before a tool runs. No tool
schema has a customer ID.

**Limits** (configurable in `application.yml`): INR 50,000 per transfer and 100,000 per day
(pending transfers count, so splitting does not help), 5 write requests per session, 2 pending
actions per customer, 30 tool calls per minute per session.

**Untrusted text.** Merchant, description, payee name and dispute reason are returned inside
`untrusted_text`, and tool descriptions tell the model that this is data, never instructions. An
injection detector flags suspicious text (`"flagged": true` plus the rules that matched), logs a
security event and writes an audit row. The text is still returned so the customer can see it.

**Audit.** Every tool call, rejected identity and action transition (created, confirmed,
executed, failed, cancelled, expired) is written to `audit_event` in the MCP server's own
database. Long digit runs in arguments are redacted.

Try it with the MCP Inspector while the stack is up:

```
TOKEN=$(scripts/mint-identity.sh 11111111-1111-4111-8111-000000000001)
npx @modelcontextprotocol/inspector --cli http://localhost:8082/mcp --transport http   --header "X-SafeLedger-Identity: $TOKEN" --method tools/list
```

Or run `npx @modelcontextprotocol/inspector` for the web UI: choose Streamable HTTP, URL
`http://localhost:8082/mcp`, and add the `X-SafeLedger-Identity` header.

## Agent service (port 8080)

Customer-facing API and chat page at `http://localhost:8080`. Pick one of the three synthetic
demo customers, chat, and press Confirm or Cancel on any request the assistant prepares.

**Model.** Amazon Bedrock through the Converse API, with Bedrock Guardrails on every customer
message and every reply. This is the intended setup. Set in `infra/.env`:

| Variable | Notes |
|---|---|
| `BEDROCK_MODEL_ID` | required; a model or inference profile your account can use in `AWS_REGION` |
| `AWS_REGION` | default `us-east-1` |
| `BEDROCK_GUARDRAIL_ID`, `BEDROCK_GUARDRAIL_VERSION` | required while guardrails are on (the default) |
| AWS credentials | default provider chain; for compose, export `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` in the shell |

The service refuses to start if Bedrock is selected without a model ID, or guardrails are on
without a guardrail ID.

**Local development without AWS.** `SAFELEDGER_CHAT_MODEL=ollama` and
`BEDROCK_GUARDRAIL_ENABLED=false` run the same agent on a local [Ollama](https://ollama.com)
model (default `qwen2.5:7b`, reached at `host.docker.internal:11434` from compose). Useful for
checking the flow at no cost; it does not exercise Bedrock or the guardrail layer, and every chat
reply reports `"guardrail": "none"`.

**What keeps the model in bounds.**

1. The MCP client is per session and signs `X-SafeLedger-Identity` for that session's customer
   on every request. The model never supplies an identity.
2. Confirm and Cancel are browser endpoints that forward to the MCP server's internal endpoints.
   The model has no tool for them; any tool name it invents gets an `UNKNOWN_TOOL` error.
3. Each turn is capped at 8 tool calls, 10 model rounds and 90 seconds.
4. The system prompt is versioned (`prompts/system-v1.md`) and every reply reports its version.
5. State-changing requests need `X-Requested-With: SafeLedger`, and the session cookie is
   `HttpOnly` and `SameSite=Strict`.

| Method and path | Notes |
|---|---|
| `GET /api/customers` | demo customers to pick from |
| `POST /api/session`, `GET /api/session`, `DELETE /api/session` | demo login, current customer, logout |
| `POST /api/chat` | `{"message"}` → reply, pending actions created this turn (id, summary, expiry), tool calls, guardrail, prompt version |
| `GET /api/actions` | this session's actions |
| `POST /api/actions/{id}/confirm`, `POST /api/actions/{id}/cancel` | the customer's buttons; never reachable from the model |

## Evaluation report

Added in Phase 5.

## Out of scope

Real authentication (OAuth, MFA) — the demo login is intentionally simple. Real payment rails or real bank integrations. A polished frontend.
