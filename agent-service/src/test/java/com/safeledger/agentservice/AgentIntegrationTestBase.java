package com.safeledger.agentservice;

import com.jayway.jsonpath.JsonPath;
import com.safeledger.agentservice.guardrail.Guardrail;
import java.io.IOException;
import java.net.CookieManager;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs agent-service on a free port against {@link FakeMcpServer} (served by the same app, so the
 * MCP client really connects over Streamable HTTP and really signs every request) with a {@link
 * ScriptedChatModel} in place of the LLM and a switchable test guardrail. Tests drive it like the
 * browser does, with a cookie jar per simulated browser.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
      "safeledger.identity.hmac-secret=" + AgentIntegrationTestBase.SECRET,
      "spring.ai.model.chat=none",
      "safeledger.guardrail.enabled=false",
      // The MCP clients' open streams to the in-app fake server would hold a graceful shutdown
      // for its full 30 s timeout.
      "server.shutdown=immediate"
    })
@Import({FakeMcpServer.Config.class, AgentIntegrationTestBase.Beans.class})
public abstract class AgentIntegrationTestBase {

  static final String SECRET = "agent-integration-test-secret-0123456789abcdef";

  /**
   * ActionsClient builds its RestClient in its constructor, before ${local.server.port} exists, so
   * the port is chosen up front. Each new context (for example one with different properties) gets
   * its own port.
   */
  @DynamicPropertySource
  static void port(DynamicPropertyRegistry registry) {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    registry.add("server.port", () -> port);
    registry.add("safeledger.mcp-url", () -> "http://localhost:" + port);
  }

  // The synthetic demo customers from application.yml.
  static final UUID RIYA = UUID.fromString("11111111-1111-4111-8111-000000000001");
  static final UUID KABIR = UUID.fromString("11111111-1111-4111-8111-000000000002");

  static final String BLOCKED_WORD = "guardrail-block-me";
  static final String BLOCKED_REPLY = "Sorry, I can't help with that.";

  @TestConfiguration(proxyBeanMethods = false)
  static class Beans {
    @Bean
    ScriptedChatModel scriptedChatModel() {
      return new ScriptedChatModel();
    }

    /** Blocks any text containing {@link #BLOCKED_WORD}, on input or output. */
    @Bean
    @Primary
    Guardrail testGuardrail() {
      return new Guardrail() {
        @Override
        public Result check(Source source, String text) {
          return text.contains(BLOCKED_WORD)
              ? new Result(true, BLOCKED_REPLY)
              : Result.passed(text);
        }

        @Override
        public String name() {
          return "test";
        }
      };
    }
  }

  @Value("${local.server.port}")
  int port;

  @Autowired ScriptedChatModel model;
  @Autowired FakeMcpServer mcp;

  @BeforeEach
  void resetFakes() {
    model.reset();
    mcp.reset();
  }

  record Response(int status, String body, HttpResponse<String> raw) {
    <T> T json(String path) {
      return JsonPath.read(body, path);
    }
  }

  /** One browser: its own cookie jar, so its own session. */
  final class Browser {
    private final HttpClient http =
        HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

    Response login(UUID customerId) {
      return post("/api/session", "{\"customerId\":\"" + customerId + "\"}");
    }

    Response chat(String message) {
      return post("/api/chat", "{\"message\":\"" + escape(message) + "\"}");
    }

    Response get(String path) {
      return send(request(path).GET());
    }

    Response post(String path, String json) {
      return send(
          request(path)
              .header("X-Requested-With", "SafeLedger")
              .header("Content-Type", "application/json")
              .POST(
                  json == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(json)));
    }

    Response postWithoutCsrf(String path, String json) {
      return send(
          request(path)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    Response delete(String path) {
      return send(request(path).header("X-Requested-With", "SafeLedger").DELETE());
    }

    HttpRequest.Builder request(String path) {
      return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    Response send(HttpRequest.Builder request) {
      try {
        HttpResponse<String> response =
            http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), response);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  Browser loggedIn(UUID customerId) {
    Browser browser = new Browser();
    Response login = browser.login(customerId);
    if (login.status() != 200) {
      throw new IllegalStateException("Login failed: " + login.status() + " " + login.body());
    }
    return browser;
  }

  static String escape(String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  static Predicate<FakeMcpServer.Caller> customer(UUID customerId) {
    return c -> c.customerId().equals(customerId);
  }
}
