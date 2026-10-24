package com.safeledger.agentservice.mcp;

import com.safeledger.agentservice.common.BrowserRequestFilter;
import com.safeledger.agentservice.config.AgentProperties;
import com.safeledger.agentservice.identity.IdentitySigner;
import com.safeledger.agentservice.session.CustomerSession;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * One MCP client per customer session. Every HTTP request it sends carries a freshly signed
 * identity for exactly that customer and session, so the MCP server always knows who is asking and
 * the model never gets a say in it. Clients are closed when the session ends.
 */
@Component
public class SessionMcpClients implements HttpSessionListener {

  private static final String HEADERS_KEY = "safeledger.headers";

  private final AgentProperties properties;
  private final IdentitySigner signer;
  private final Tracer tracer;
  private final Propagator propagator;
  private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

  public SessionMcpClients(
      AgentProperties properties,
      IdentitySigner signer,
      ObjectProvider<Tracer> tracer,
      ObjectProvider<Propagator> propagator) {
    this.properties = properties;
    this.signer = signer;
    this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
    this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
  }

  public McpSyncClient forSession(CustomerSession session) {
    return clients.computeIfAbsent(session.sessionId(), id -> connect(session));
  }

  private McpSyncClient connect(CustomerSession session) {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder(properties.mcpUrl())
            .endpoint("/mcp")
            .httpRequestCustomizer(
                (builder, method, uri, body, context) -> {
                  builder.header(
                      IdentitySigner.HEADER,
                      signer.sign(session.customerId(), session.sessionId()));
                  if (context != null && context.get(HEADERS_KEY) instanceof Map<?, ?> headers) {
                    headers.forEach((k, v) -> builder.header(k.toString(), v.toString()));
                  }
                })
            .build();
    McpSyncClient client =
        McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(30))
            .transportContextProvider(this::callerContext)
            .build();
    client.initialize();
    return client;
  }

  /**
   * Runs on the thread making the MCP call, which may not be the thread sending the HTTP request:
   * captures the correlation ID and trace headers there.
   */
  private McpTransportContext callerContext() {
    Map<String, String> headers = new HashMap<>();
    String correlationId = MDC.get(BrowserRequestFilter.MDC_KEY);
    if (correlationId != null) {
      headers.put(BrowserRequestFilter.CORRELATION_HEADER, correlationId);
    }
    TraceContext trace = tracer.currentTraceContext().context();
    if (trace != null) {
      propagator.inject(trace, headers, Map::put);
    }
    return McpTransportContext.create(Map.of(HEADERS_KEY, headers));
  }

  @Override
  public void sessionDestroyed(HttpSessionEvent event) {
    Object value = event.getSession().getAttribute("safeledger.customerSession");
    if (value instanceof CustomerSession session) {
      close(session.sessionId());
    }
  }

  void close(String sessionId) {
    McpSyncClient client = clients.remove(sessionId);
    if (client != null) {
      client.closeGracefully();
    }
  }

  @PreDestroy
  void closeAll() {
    clients.keySet().forEach(this::close);
  }
}
