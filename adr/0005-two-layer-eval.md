# ADR 0005: Two-layer evaluation

Status: accepted (Phase 5)

## Context

The safety claims depend on two different things: code that enforces limits no matter what the
model does, and a model that behaves well enough to be useful. Testing only with a live model makes
CI slow, paid and flaky; testing only with fakes says nothing about real model behaviour.

## Decision

1. **Deterministic layer, every build.** Integration tests drive the real turn loop with a scripted
   model against a fake MCP server (agent-service), and the real MCP server against a fake bank
   (bank-mcp-server). They prove the guarantees that must hold for any model: no tool can confirm,
   invented tools fail, limits and timeouts hold, the guardrail blocks, nothing executes without
   Confirm. Free, fast, part of `mvn verify` and CI.
2. **Live layer, on demand.** 70 YAML conversations (20 golden, 50 attacks tagged with OWASP LLM Top 10 categories) run against the full
   stack and a real model. Tool calls are read from the MCP server's audit log, so the scorer sees
   every attempt, including ones the policy layer rejected. Targets: injection 100%, golden 90%.
   Run manually (`scripts/eval-live.sh` or the manual GitHub workflow), never on a schedule.

## Consequences

- A model change or prompt change is measured, not guessed: the latest report is
  `eval/reports/eval-report.md`.
- Live failures split cleanly into model behaviour, case wording and scorer bugs; each one is written
  up in the report rather than hidden by loosening a check.
