package com.safeledger.agentservice;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stand-in for bank-mcp-server, served by the test app itself: a real MCP server over Streamable
 * HTTP at /mcp with a few tools, and the internal confirm/cancel/list endpoints. Like the real
 * server it verifies the signed identity header on every request and rejects anything without one,
 * and it scopes actions to the caller's customer and session. It records every identity it saw and
 * every execution, so tests can prove what agent-service sent and what actually ran.
 */
@RestController
public class FakeMcpServer {

  static final String HEADER = "X-SafeLedger-Identity";
  static final String ATTRIBUTE = "fake.caller";

  /** Tools the fake advertises. Same names as the real server; no confirm tool, as there. */
  static final List<String> TOOLS = List.of("get_balance", "request_card_freeze");

  public record Caller(UUID customerId, String sessionId) {}

  public record Action(
      UUID id,
      String tool,
      String summary,
      String status,
      Instant createdAt,
      Instant expiresAt,
      Instant executedAt,
      Map<String, Object> result,
      Caller owner) {

    Action withStatus(String newStatus, Instant executed, Map<String, Object> newResult) {
      return new Action(
          id, tool, summary, newStatus, createdAt, expiresAt, executed, newResult, owner);
    }

    Map<String, Object> view() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", id);
      view.put("tool", tool);
      view.put("summary", summary);
      view.put("status", status);
      view.put("createdAt", createdAt);
      view.put("expiresAt", expiresAt);
      view.put("executedAt", executedAt);
      view.put("result", result);
      return view;
    }
  }

  /** Every verified identity, one per HTTP request (MCP or internal). */
  final List<Caller> callers = new CopyOnWriteArrayList<>();

  /** Tool calls as the MCP server received them. */
  final List<String> toolCalls = new CopyOnWriteArrayList<>();

  /** Tracing headers of every verified request, to check propagation from agent-service. */
  record Seen(String path, String traceparent, String correlationId) {}

  final List<Seen> requests = new CopyOnWriteArrayList<>();

  final Map<UUID, Action> actions = new ConcurrentHashMap<>();
  final AtomicInteger executions = new AtomicInteger();
  final AtomicInteger rejectedRequests = new AtomicInteger();

  void reset() {
    callers.clear();
    toolCalls.clear();
    requests.clear();
    actions.clear();
    executions.set(0);
    rejectedRequests.set(0);
  }

  /** Creates a pending action for this caller, as a request_ tool would. */
  Action createPending(Caller caller, String tool, String summary) {
    Instant now = Instant.now();
    Action action =
        new Action(
            UUID.randomUUID(),
            tool,
            summary,
            "PENDING",
            now,
            now.plus(5, ChronoUnit.MINUTES),
            null,
            null,
            caller);
    actions.put(action.id(), action);
    return action;
  }

  // ---- internal endpoints (not MCP tools) ----

  @GetMapping("/internal/actions")
  public List<Map<String, Object>> list(@RequestAttribute(ATTRIBUTE) Caller caller) {
    return actions.values().stream()
        .filter(a -> a.owner().equals(caller))
        .map(Action::view)
        .toList();
  }

  @PostMapping("/internal/actions/{id}/confirm")
  public synchronized ResponseEntity<?> confirm(
      @PathVariable UUID id, @RequestAttribute(ATTRIBUTE) Caller caller) {
    Action action = actions.get(id);
    if (action == null || !action.owner().customerId().equals(caller.customerId())) {
      return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Action not found");
    }
    if (!"PENDING".equals(action.status())) {
      return problem(HttpStatus.CONFLICT, "ACTION_NOT_PENDING", "Already " + action.status());
    }
    executions.incrementAndGet();
    Action executed = action.withStatus("EXECUTED", Instant.now(), Map.of("cardStatus", "FROZEN"));
    actions.put(id, executed);
    return ResponseEntity.ok(executed.view());
  }

  @PostMapping("/internal/actions/{id}/cancel")
  public synchronized ResponseEntity<?> cancel(
      @PathVariable UUID id, @RequestAttribute(ATTRIBUTE) Caller caller) {
    Action action = actions.get(id);
    if (action == null || !action.owner().customerId().equals(caller.customerId())) {
      return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Action not found");
    }
    if (!"PENDING".equals(action.status())) {
      return problem(HttpStatus.CONFLICT, "ACTION_NOT_PENDING", "Already " + action.status());
    }
    Action cancelled = action.withStatus("CANCELLED", null, null);
    actions.put(id, cancelled);
    return ResponseEntity.ok(cancelled.view());
  }

  private static ResponseEntity<?> problem(HttpStatus status, String code, String detail) {
    return ResponseEntity.status(status).body(Map.of("code", code, "detail", detail));
  }

  // ---- MCP tools ----

  private McpSchema.CallToolResult callTool(
      String name, McpTransportContext context, Map<String, Object> args) {
    Caller caller = (Caller) context.get(ATTRIBUTE);
    toolCalls.add(name);
    String text =
        switch (name) {
          case "get_balance" ->
              "{\"accountType\":\"SAVINGS\",\"currency\":\"INR\",\"balance\":250000.00}";
          case "request_card_freeze" -> {
            Action action =
                createPending(caller, name, "Freeze card ending " + args.get("cardLast4"));
            yield "{\"status\":\"PENDING_CONFIRMATION\",\"actionId\":\""
                + action.id()
                + "\",\"summary\":\""
                + action.summary()
                + "\"}";
          }
          default -> throw new IllegalArgumentException(name);
        };
    return McpSchema.CallToolResult.builder().addTextContent(text).isError(false).build();
  }

  private McpServerFeatures.SyncToolSpecification tool(String name, Map<String, Object> schema) {
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(
            McpSchema.Tool.builder()
                .name(name)
                .description("Fake " + name)
                .inputSchema(schema)
                .build())
        .callHandler(
            (exchange, request) -> callTool(name, exchange.transportContext(), request.arguments()))
        .build();
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class Config {

    @Bean
    HttpServletStreamableServerTransportProvider fakeMcpTransport() {
      return HttpServletStreamableServerTransportProvider.builder()
          .mcpEndpoint("/mcp")
          .contextExtractor(
              request ->
                  McpTransportContext.create(Map.of(ATTRIBUTE, request.getAttribute(ATTRIBUTE))))
          .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> fakeMcpServlet(
        HttpServletStreamableServerTransportProvider transport) {
      ServletRegistrationBean<HttpServletStreamableServerTransportProvider> bean =
          new ServletRegistrationBean<>(transport, "/mcp");
      bean.setAsyncSupported(true);
      return bean;
    }

    @Bean(destroyMethod = "close")
    McpSyncServer fakeMcpSyncServer(
        HttpServletStreamableServerTransportProvider transport, FakeMcpServer fake) {
      return McpServer.sync(transport)
          .serverInfo("fake-bank-mcp-server", "test")
          .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
          .tools(
              fake.tool("get_balance", Map.of("type", "object", "properties", Map.of())),
              fake.tool(
                  "request_card_freeze",
                  Map.of(
                      "type",
                      "object",
                      "properties",
                      Map.of("cardLast4", Map.of("type", "string")),
                      "required",
                      List.of("cardLast4"))))
          .build();
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> fakeIdentityFilter(FakeMcpServer fake) {
      FilterRegistrationBean<OncePerRequestFilter> bean =
          new FilterRegistrationBean<>(new IdentityFilter(fake));
      bean.addUrlPatterns("/mcp", "/mcp/*", "/internal/*");
      return bean;
    }
  }

  /** Verifies the header the way bank-mcp-server's IdentityToken does, and records the caller. */
  static final class IdentityFilter extends OncePerRequestFilter {

    private final FakeMcpServer fake;

    IdentityFilter(FakeMcpServer fake) {
      this.fake = fake;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      Caller caller = verify(request.getHeader(HEADER));
      if (caller == null) {
        fake.rejectedRequests.incrementAndGet();
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return;
      }
      fake.callers.add(caller);
      fake.requests.add(
          new Seen(
              request.getRequestURI(),
              request.getHeader("traceparent"),
              request.getHeader("X-Correlation-Id")));
      request.setAttribute(ATTRIBUTE, caller);
      chain.doFilter(request, response);
    }

    static Caller verify(String token) {
      if (token == null) {
        return null;
      }
      String[] parts = token.split("\\.");
      if (parts.length != 2) {
        return null;
      }
      try {
        byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(
            new SecretKeySpec(
                AgentIntegrationTestBase.SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        if (!MessageDigest.isEqual(mac.doFinal(payload), Base64.getUrlDecoder().decode(parts[1]))) {
          return null;
        }
        String[] fields = new String(payload, StandardCharsets.UTF_8).split("\\|");
        if (fields.length != 4 || !"v1".equals(fields[0])) {
          return null;
        }
        long age = Instant.now().getEpochSecond() - Long.parseLong(fields[3]);
        if (age < -30 || age > 300) {
          return null;
        }
        return new Caller(UUID.fromString(fields[1]), fields[2]);
      } catch (Exception e) {
        return null;
      }
    }
  }
}
