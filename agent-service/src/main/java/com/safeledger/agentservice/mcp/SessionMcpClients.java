package com.safeledger.agentservice.mcp;

import com.safeledger.agentservice.common.BrowserRequestFilter;
import com.safeledger.agentservice.config.AgentProperties;
import com.safeledger.agentservice.identity.IdentitySigner;
import com.safeledger.agentservice.session.CustomerSession;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * One MCP client per customer session. Every HTTP request it sends carries a freshly signed
 * identity for exactly that customer and session, so the MCP server always knows who is asking and
 * the model never gets a say in it. Clients are closed when the session ends.
 */
@Component
public class SessionMcpClients implements HttpSessionListener {

  private final AgentProperties properties;
  private final IdentitySigner signer;
  private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

  public SessionMcpClients(AgentProperties properties, IdentitySigner signer) {
    this.properties = properties;
    this.signer = signer;
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
                  String correlationId = MDC.get(BrowserRequestFilter.MDC_KEY);
                  if (correlationId != null) {
                    builder.header(BrowserRequestFilter.CORRELATION_HEADER, correlationId);
                  }
                })
            .build();
    McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
    client.initialize();
    return client;
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
