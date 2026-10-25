package com.safeledger.bankmcpserver;

import com.jayway.jsonpath.JsonPath;
import com.safeledger.bankmcpserver.identity.CallerIdentity;
import com.safeledger.bankmcpserver.identity.IdentityToken;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the real MCP server on a random port with a Testcontainers Postgres for actions and audit,
 * and the in-memory {@link FakeBank} (served by the same app) as the bank. Tests talk to it the way
 * agent-service will: an MCP client over Streamable HTTP plus plain HTTP for confirm/cancel, both
 * carrying a signed identity header.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "safeledger.bank.base-url=http://localhost:${local.server.port}/fake-bank",
      "safeledger.identity.hmac-secret=" + McpIntegrationTestBase.SECRET,
      // Tests trigger expiry themselves; keep the background sweep out of the way.
      "safeledger.actions.expiry-sweep-interval=1h",
      // These suites call write tools directly, with no customer messages; ProvenanceIT turns the
      // check on and tests it.
      "safeledger.provenance.enabled=${test.provenance:false}"
    })
@Import(McpIntegrationTestBase.Containers.class)
abstract class McpIntegrationTestBase {

  static final String SECRET = "integration-test-secret-0123456789abcdefghij";

  @TestConfiguration(proxyBeanMethods = false)
  static class Containers {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
      return new PostgreSQLContainer("postgres:16");
    }
  }

  static final HttpClient HTTP = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  int port;

  @Autowired FakeBank bank;
  @Autowired JdbcTemplate jdbc;

  private final List<McpSyncClient> clients = new ArrayList<>();

  @AfterEach
  void closeClients() {
    clients.forEach(McpSyncClient::closeGracefully);
    clients.clear();
  }

  /** A customer nobody else uses, with fresh fake bank data. */
  static UUID newCustomer() {
    return UUID.randomUUID();
  }

  static String newSession() {
    return "s-" + UUID.randomUUID();
  }

  static String token(UUID customerId, String sessionId) {
    return IdentityToken.sign(
        new CallerIdentity(customerId, sessionId),
        Instant.now(),
        SECRET.getBytes(StandardCharsets.UTF_8));
  }

  McpSyncClient connect(UUID customerId, String sessionId) {
    return connect(customerId, sessionId, Map.of());
  }

  McpSyncClient connect(UUID customerId, String sessionId, Map<String, String> extraHeaders) {
    String token = token(customerId, sessionId);
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
            .endpoint("/mcp")
            .httpRequestCustomizer(
                (builder, method, uri, body, context) -> {
                  builder.header(IdentityToken.HEADER, token);
                  extraHeaders.forEach(builder::header);
                })
            .build();
    McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
    clients.add(client);
    client.initialize();
    return client;
  }

  /** Result of a tool call: its text (JSON) and whether MCP marked it as an error. */
  record ToolResult(String text, boolean isError) {
    <T> T json(String path) {
      return JsonPath.read(text, path);
    }
  }

  static ToolResult call(McpSyncClient client, String tool, Map<String, Object> args) {
    McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, args));
    String text = ((McpSchema.TextContent) result.content().getFirst()).text();
    return new ToolResult(text, Boolean.TRUE.equals(result.isError()));
  }

  record HttpResult(int status, String body) {
    <T> T json(String path) {
      return JsonPath.read(body, path);
    }
  }

  HttpResult confirm(UUID customerId, String sessionId, Object actionId) {
    return postInternal("/internal/actions/" + actionId + "/confirm", token(customerId, sessionId));
  }

  HttpResult cancel(UUID customerId, String sessionId, Object actionId) {
    return postInternal("/internal/actions/" + actionId + "/cancel", token(customerId, sessionId));
  }

  HttpResult postInternal(String path, String identityToken) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .POST(HttpRequest.BodyPublishers.noBody());
    if (identityToken != null) {
      request.header(IdentityToken.HEADER, identityToken);
    }
    return send(request);
  }

  HttpResult send(HttpRequest.Builder request) {
    try {
      HttpResponse<String> response =
          HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new HttpResult(response.statusCode(), response.body());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  String actionStatus(Object actionId) {
    return jdbc.queryForObject(
        "select status from pending_action where id = ?::uuid", String.class, actionId.toString());
  }

  int audit(String eventType, Object actionId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from audit_event where event_type = ? and action_id = ?::uuid",
            Integer.class,
            eventType,
            actionId.toString());
    return n == null ? 0 : n;
  }
}
