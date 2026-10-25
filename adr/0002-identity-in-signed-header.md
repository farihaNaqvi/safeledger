# ADR 0002: Identity comes from a signed header, never from tool arguments

Status: accepted (Phase 3)

## Context

Every MCP tool acts for one customer. If the customer were a tool argument, the model would choose
it, and anything that steers the model (an injected payee name, a "show me customer X" message)
could point a tool at someone else's data. This is the confused deputy problem: the MCP server has
the authority, the model decides how it is used.

## Decision

1. No tool schema has a customer ID. `ToolCatalogIT.noToolSchemaAcceptsACustomerId` fails the build
   if one appears.
2. agent-service knows the customer from the browser session and adds `X-SafeLedger-Identity` to
   every request to the MCP server: `base64url("v1|customer|session|issuedAt").base64url(HMAC-SHA256)`,
   valid for 5 minutes. The secret is shared only by the two services.
3. The MCP server verifies the token in a servlet filter before any MCP or internal endpoint runs and
   answers `401` otherwise. Tools read the caller from the verified token only.
4. The bank API scopes every query by that customer and answers `404` for anything owned by someone
   else, the same as for something that does not exist.

## Alternatives

- **Customer ID as a tool argument, checked against the session.** Works only if every tool
  remembers to check; one missed check leaks data. Rejected.
- **mTLS or OAuth between services.** Stronger, but heavier than this demo needs. The header carries
  the same claims and can be swapped for a JWT later.

## Consequences

The model cannot reach another customer even if it is fully manipulated: the worst it can do is
call a tool with an ID that the bank then reports as not found. Tests: `IdentityTokenTest`,
`IdentityIT` (missing, forged, expired, tampered tokens; `customerIdInToolArgumentsIsIgnored`;
`cannotConfirmAnotherCustomersAction`), `OwnershipIT`, and the cross-customer eval cases.
