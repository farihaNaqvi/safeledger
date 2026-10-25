# ADR 0003: MCP over Streamable HTTP

Status: accepted (Phase 3)

## Context

The MCP server runs as its own service, so the transport has to cross a network. MCP offers stdio
(the server is a child process), the older HTTP+SSE transport, and Streamable HTTP.

## Decision

Use Streamable HTTP at `/mcp` (Spring AI MCP server, `protocol: STREAMABLE`; MCP Java SDK client
`HttpClientStreamableHttpTransport` in agent-service, one client per chat session).

## Why

1. **It is plain HTTP.** Every request passes through normal servlet filters, so the signed identity
   header (ADR 0002), correlation ID and tracing work exactly as for any endpoint, and a request
   without a valid identity is refused before the MCP layer sees it.
2. **It deploys like any web service:** one port behind a security group or load balancer, health
   checks, Cloud Map discovery. stdio would tie the server to the agent's process and remove the
   trust boundary between them.
3. **It is the current transport in the MCP specification**; HTTP+SSE is deprecated.

## Consequences

- Per-request context (identity, correlation ID, trace) has to be carried into the MCP SDK
  explicitly, because tool handlers and client requests can run off the request thread. The server
  copies it into the MCP transport context; the client uses the SDK's transport context provider.
  `ObservabilityIT` in both services checks this.
- The MCP endpoint must never be public. In AWS it accepts traffic only from agent-service's security
  group (`InfrastructureTest`).
