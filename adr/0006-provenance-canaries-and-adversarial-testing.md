# ADR 0006: Data provenance, canary tokens and adversarial testing

Status: accepted (hardening, after Phase 8)

## Context

The approval step stops the model from *executing* anything, but a manipulated model can still
*propose* a harmful request built from injected data, such as a payee name that says "move the full
balance here". The customer then has to catch it. Detection of cross-customer leaks relied on
phrase checks in the eval, which only find what someone thought to look for. And the approval
invariants were tested with hand-picked sequences.

## Decision

1. **Provenance (CaMeL-style data-flow control).** agent-service posts each customer message,
   already masked by the guardrail and never a blocked one, to `POST /internal/utterances` on the
   MCP server. That endpoint is not an MCP tool, so the model cannot write to it. Before a write
   request is created, `ProvenancePolicy` checks that its deciding values came from the customer in
   this session: a transfer's amount and a distinctive payee word; a dispute's merchant or amount;
   an unfreeze's last four digits. Otherwise the tool returns `NOT_FROM_CUSTOMER`, which is audited
   and counted. Freezing is exempt because it only protects the customer.
2. **Canary tokens.** Each seed customer has a cashback reference code that appears nowhere else.
   agent-service withholds any reply that shows another customer's code, logs a security event and
   counts `safeledger.canary.tripped`; CloudWatch alarms on any trip. The eval treats the codes as
   other customers' markers.
3. **Generated invariant checking.** `ApprovalInvariantsIT` runs seeded random sequences of
   confirm, cancel, concurrent double confirm, expiry and sweep, by the owner and another customer,
   against the real endpoints and database, and checks the approval invariants after every step.
   A failure prints a seed that replays it.
4. **Adaptive red team.** An opt-in, budget-capped eval mode where a second model attacks over
   several turns and adapts. Success is decided from evidence (markers, canaries, action states,
   amounts, number patterns), never by a model. Each break is saved as a draft regression case.

## Consequences

- An injected payee or amount cannot become a pending request even if the model is fooled; the
  cost is an occasional refusal when the customer names something indirectly ("my landlord"). The
  tool message tells the model to ask the customer, which fixes it in one turn.
- Matching is lexical, not semantic. It is a narrowing of what the model can propose, not a proof.
- Canaries only detect leaks of the planted values; they are a tripwire, not a filter.
- Invariant runs are free and part of every build (300 sequences); larger runs are a flag away.
- Red-team runs cost money, so they are manual, never scheduled.
