# ADR 0004: Synthetic data only

Status: accepted (Phase 1)

## Context

This is a public demo of a banking assistant. Realistic data makes the demo convincing; real data
would make it a liability, and anything typed into it may be sent to a model provider.

## Decision

1. All customers, accounts, cards, payees and transactions are invented: three demo customers,
   about 200 transactions over 90 days, and six deliberately poisoned records for injection tests.
2. Card numbers exist only as last four digits; payee accounts only masked. No full card or account
   number is stored anywhere.
3. No real names, employers, phone numbers or emails. Names are visibly fake ("Riya Sample",
   "Kabir Example", "Meera Testcase").
4. The guardrail masks full card and account numbers that a user types before the model sees them,
   the audit trail redacts long digit runs in tool arguments, and chat text is never logged, so even
   a careless demo user does not leave real numbers in the system.

## Consequences

- Seed data can be committed, reset at will (`docker compose down -v`) and deleted with the AWS
  stack without snapshots (`InfrastructureTest.nothingIsRetainedOrSnapshottedOnDestroy`).
- Tests check it: `SeedDataIT.containsNoFullCardOrAccountNumbers`,
  `SeedDataIT.usesOnlySyntheticContactDetails`, `AccountCardPayeeIT.listsCardsWithLast4Only`.
