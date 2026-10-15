# SafeLedger

A guarded conversational banking assistant for a fictional bank, built on MCP, Spring AI and Amazon Bedrock. The point of the project is the engineering around the agent — confirmation outside the model's reach, idempotent and audited writes, identity from the session rather than tool arguments, and prompt-injection detection with automated tests — not the chat itself.

All data in this repo is synthetic. No real card numbers, names or employer data.

## Status

Building phase by phase. Currently: **Phase 2 — mock bank API**.

| Phase | Status |
|---|---|
| 0. Manual setup | done outside this repo |
| 1. Project skeleton | done |
| 2. Mock bank API | done |
| 3. MCP server and policy layer | not started |
| 4. Agent service | not started |
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

## Evaluation report

Added in Phase 5.

## Out of scope

Real authentication (OAuth, MFA) — the demo login is intentionally simple. Real payment rails or real bank integrations. A polished frontend.
