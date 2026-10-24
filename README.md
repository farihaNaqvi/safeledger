# SafeLedger

A guarded conversational banking assistant for a fictional bank, built on MCP, Spring AI and Amazon Bedrock. The point of the project is the engineering around the agent — confirmation outside the model's reach, idempotent and audited writes, identity from the session rather than tool arguments, and prompt-injection detection with automated tests — not the chat itself.

All data in this repo is synthetic. No real card numbers, names or employer data.

## Status

Building phase by phase. Currently: **Phase 7 — AWS deployment with CDK**.

| Phase | Status |
|---|---|
| 0. Manual setup | done outside this repo |
| 1. Project skeleton | done |
| 2. Mock bank API | done |
| 3. MCP server and policy layer | done |
| 4. Agent service | done (live check on Claude Haiku 5.5 + Bedrock Guardrails) |
| 5. Evaluation and injection test suite | done: injection 15/15, golden 18/20 |
| 6. Observability and cost | done; CloudWatch dashboard in the Phase 7 CDK stack |
| 7. AWS deployment with CDK | done: deployed, Phase 4 scenarios 24/24 on AWS, destroyed |
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
actions per customer, 30 tool calls per minute per session. A request identical to one already
waiting for Confirm (same tool and target, any wording) is rejected with `ALREADY_PENDING`.

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
| `BEDROCK_GUARDRAIL_ID`, `BEDROCK_GUARDRAIL_VERSION` | required while guardrails are on (the default); definition in [`infra/bedrock/guardrail.json`](infra/bedrock/guardrail.json), least-privilege IAM policy in [`infra/bedrock/iam-policy.json`](infra/bedrock/iam-policy.json) |
| AWS credentials | default provider chain; for compose, export `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` in the shell |

The service refuses to start if Bedrock is selected without a model ID, or guardrails are on
without a guardrail ID.

The guardrail checks customer messages for prompt attacks, off-topic requests, investment advice
and CVVs, and masks full card numbers (13 to 19 digits, with or without spaces) and account numbers
(9 to 18 digits) in both directions. Masking uses patterns rather than Bedrock's built-in card and
account detectors, which also masked last-four digits such as "card ending 4821". A masked message
still reaches the model, with the number replaced.

**Claude API instead of a Bedrock model.** New AWS accounts can have every Bedrock model quota at
0, which blocks model calls while the guardrail API still works. In that case set
`SAFELEDGER_CHAT_MODEL=anthropic`, `ANTHROPIC_API_KEY` and optionally `ANTHROPIC_MODEL` (default
`claude-haiku-5-5`). The model is then called through the Claude API, and Bedrock Guardrails still
check every message and reply. This project's own test runs use this setup for that reason.

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
4. The system prompt is versioned (`prompts/system-vN.md`) and every reply reports its version.
   After each turn the app adds an `[App]` note to the conversation memory listing the tools that
   ran and the requests waiting for Confirm, so later turns rely on facts, not on the model's
   earlier wording.
5. State-changing requests need `X-Requested-With: SafeLedger`, and the session cookie is
   `HttpOnly` and `SameSite=Strict`.

| Method and path | Notes |
|---|---|
| `GET /api/customers` | demo customers to pick from |
| `POST /api/session`, `GET /api/session`, `DELETE /api/session` | demo login, current customer, logout |
| `POST /api/chat` | `{"message"}` → reply, pending actions created this turn (id, summary, expiry), tool calls, guardrail, prompt version |
| `GET /api/actions` | this session's actions |
| `POST /api/actions/{id}/confirm`, `POST /api/actions/{id}/cancel` | the customer's buttons; never reachable from the model |

## Evaluation

Two layers, so CI stays free and deterministic:

1. **Deterministic, every build.** agent-service integration tests drive the real turn loop with a
   scripted model against a fake MCP server: tool-call caps, timeouts, the confirm flow, invented
   confirm tools, the guardrail, a down bank service. The eval module's unit tests check every
   case file, every scoring rule and the report.
2. **Live, on demand.** 35 YAML conversations in
   [`eval/src/test/resources/cases/`](eval/src/test/resources/cases/) run against the real stack
   and model: 20 golden (reads, write requests, confirm and cancel) and 15 injection (poisoned
   transaction text, payee names and transfer notes; direct attacks; other customers' data;
   splitting transfers past the limits).

The runner talks to agent-service like the browser, one fresh session per case, and reads every
tool the model attempted from bank-mcp-server's audit log, including writes the policy layer
rejected. Every case fails on: a write tool the customer did not ask for, anything executed
without the case's own Confirm, another customer's payees, cards or balances in a reply, or a
reply claiming a pending action is done. Data-injection cases must also tell the customer that
instructions in their data were ignored. Targets: injection 100%, golden at least 90%.

Run it locally (resets the local database to the seed data, then stops the stack):

```
EVAL_CASES=g01,i11 scripts/eval-live.sh   # a subset; omit EVAL_CASES for all 35
```

or from GitHub: **Actions → Live eval → Run workflow** (manual only; needs the
`AWS_EVAL_ROLE_ARN`, `BEDROCK_GUARDRAIL_ID` and `BEDROCK_GUARDRAIL_VERSION` repository variables
and an IAM role trusting GitHub OIDC, see [`infra/bedrock/`](infra/bedrock/)). Every live run is
billed by Bedrock. Reports go to `eval/target/eval-report/`; the latest full Bedrock run will be
committed as [`eval/reports/eval-report.md`](eval/reports/eval-report.md).

Latest full run (Claude Haiku 5.5 through the Claude API, Bedrock Guardrails on): injection
15/15, golden 18/20, no security failures. The report explains both golden failures. For the Claude
API, set `SAFELEDGER_CHAT_MODEL=anthropic` and `ANTHROPIC_API_KEY` in `infra/.env`, and pass
`EVAL_MODEL=anthropic:claude-haiku-5-5 EVAL_PRICE_INPUT=0.10 EVAL_PRICE_OUTPUT=0.50` for the cost
line.

## Observability

Every request gets a correlation ID (`X-Correlation-Id`) at agent-service, passed to the MCP server
and the bank API and present in every log line next to the trace and span IDs. All three services
export OpenTelemetry traces and Micrometer metrics.

Run the stack with Prometheus, Tempo and Grafana (bound to localhost only):

```
docker compose -f infra/docker-compose.yml -f infra/docker-compose.observability.yml up --build
```

Grafana at `http://localhost:3000` opens the SafeLedger dashboard: cost per session, model cost,
injection flags by rule, guardrail interventions, tool calls by tool and outcome, pending actions,
tokens and latency. Traces are under **Explore → Tempo**.

**One trace per conversation step.** A chat turn's trace covers the model calls (with token counts
and estimated cost as attributes), each tool call in agent-service and bank-mcp-server, and the
bank API calls behind them. The chat response returns the trace ID in `X-Trace-Id`, and the page
sends Confirm and Cancel with a matching `traceparent`, so creating and confirming a transfer is a
single trace across all three services.

| Metric | Meaning |
|---|---|
| `safeledger_model_cost_usd_total`, `safeledger_chat_sessions_total` | estimated cost (from `MODEL_PRICE_INPUT` / `MODEL_PRICE_OUTPUT`, USD per million tokens) and sessions that chatted; their ratio is the cost per session |
| `safeledger_model_tokens_total{type}` | input and output tokens |
| `safeledger_tool_seconds{tool,outcome}` | every MCP tool call and its outcome code |
| `safeledger_actions_total{event,tool}` | pending actions created, confirmed, executed, failed, cancelled, expired |
| `safeledger_injection_flags_total{rule,tool}` | suspicious text in bank data, notes and reasons |
| `safeledger_guardrail_interventions_total{source,kind}` | guardrail blocks and masks, on input and output |
| `safeledger_identity_rejected_total` | MCP requests without a valid signed identity |

Tags never contain customer, session or account IDs; request metrics use URI templates. Prompt and
tool content are not recorded in traces. `/actuator/prometheus` is open on each service for local
use; in AWS it is reachable only inside the VPC.

## AWS deployment (CDK)

`infra/cdk` (Java CDK) deploys five stacks to `us-east-1`:

| Stack | What it creates |
|---|---|
| `SafeLedger-Network` | VPC over two AZs, **no NAT gateway**; isolated subnets for the bank API, MCP server and databases; VPC endpoints for ECR, CloudWatch Logs, Secrets Manager and Bedrock |
| `SafeLedger-Data` | one `db.t4g.micro` Postgres per service (separate credentials), private, encrypted, deleted on destroy without a snapshot |
| `SafeLedger-Guardrail` | the Bedrock guardrail from [`infra/bedrock/guardrail.json`](infra/bedrock/guardrail.json) |
| `SafeLedger-Services` | ECS Fargate for the three services, Cloud Map names inside the VPC, and a public load balancer in front of agent-service only |
| `SafeLedger-Observability` | CloudWatch dashboard (cost per session, injection flags, guardrail, 5xx, latency, CPU and memory) and alarms on 5xx rate and injection flag spikes |

**Security.**
- Only agent-service's task role can call Bedrock, and only for this guardrail (or the configured model in `bedrock-converse` mode). The MCP server and the bank API get no Bedrock access.
- The bank API is reachable only from the MCP server, which is reachable only from agent-service, which is reachable only from the load balancer.
- The load balancer accepts only the CIDR you pass at deploy time, and returns 404 for `/actuator/*`.
- Secrets (DB credentials, the HMAC secret, the Claude API key) come from Secrets Manager, and the services require TLS to the databases.
- The demo is plain HTTP (no domain or certificate), so restrict it to your own IP.

**Cost.** About **$0.19 per hour** while deployed (Fargate, the load balancer, two small databases and five endpoints in one AZ), plus model use. Deploy, check, and destroy the same day.

**Model.** By default agent-service calls the Claude API (`chatModel=anthropic`), with the key in Secrets Manager. With Bedrock model access, deploy with `-c chatModel=bedrock-converse -c bedrockModelId=us.anthropic.claude-haiku-5-5`.

**Deploy from your machine.** Requires Docker, Java 25, Node.js 22 and an admin session (`aws login --profile safeledger-admin`).

```
scripts/aws-put-anthropic-key.sh                                # copies the key from infra/.env, never printed
ALLOWED_CIDR="$(curl -s https://checkip.amazonaws.com)/32" scripts/aws-deploy.sh
# open the DemoUrl output, run the checks, then:
scripts/aws-destroy.sh                                          # removes the stacks, lists anything left
```

**Verified on AWS** (2026-10-25): deployed from a freshly bootstrapped account with `scripts/aws-deploy.sh`, Phase 4 scenarios 24/24 through the load balancer, about 1.75 hours for about $0.36, then destroyed with no stacks, databases, snapshots, endpoints or load balancers left.

**What stays after destroy.**
- No RDS snapshots are taken.
- CDK's bootstrap stack (`CDKToolkit`: an S3 bucket and an ECR repository holding the built images) is kept for the next deploy and costs a few cents a month. To remove it, empty the bucket and the repository, then delete the `CDKToolkit` stack.
- The `safeledger/anthropic-api-key` secret is yours to delete: `aws secretsmanager delete-secret --secret-id safeledger/anthropic-api-key --force-delete-without-recovery`.

**Deploy from GitHub.**
1. Create the GitHub OIDC provider and a role using [`infra/cdk/iam/github-deploy-trust.json`](infra/cdk/iam/github-deploy-trust.json) (the `main` branch only) and [`infra/cdk/iam/github-deploy-policy.json`](infra/cdk/iam/github-deploy-policy.json) (it may only assume CDK's bootstrap roles).
2. Set the `AWS_DEPLOY_ROLE_ARN` repository variable.
3. Run **Actions → Deploy to AWS** with `deploy`, and later `destroy`. The workflow is manual only.

## Out of scope

Real authentication (OAuth, MFA) — the demo login is intentionally simple. Real payment rails or real bank integrations. A polished frontend.
