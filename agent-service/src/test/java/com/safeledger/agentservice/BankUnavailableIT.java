package com.safeledger.agentservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** With the MCP server unreachable, the browser API answers 503 with a readable message. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "safeledger.mcp-url=http://localhost:1",
      "safeledger.identity.hmac-secret=" + AgentIntegrationTestBase.SECRET,
      "spring.ai.model.chat=none",
      "safeledger.guardrail.enabled=false",
      "server.shutdown=immediate"
    })
@Import(AgentIntegrationTestBase.Beans.class)
class BankUnavailableIT {

  @Value("${local.server.port}")
  int port;

  @Autowired ScriptedChatModel model;

  private final HttpClient http =
      HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

  private HttpResponse<String> post(String path, String json) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("X-Requested-With", "SafeLedger")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void chatAndActionsAnswer503WhenTheMcpServerIsDown() throws Exception {
    post("/api/session", "{\"customerId\":\"" + AgentIntegrationTestBase.RIYA + "\"}");

    HttpResponse<String> chat = post("/api/chat", "{\"message\":\"What is my balance?\"}");
    HttpResponse<String> confirm = post("/api/actions/" + UUID.randomUUID() + "/confirm", "{}");

    assertThat(chat.statusCode()).isEqualTo(503);
    assertThat(JsonPath.<String>read(chat.body(), "$.code")).isEqualTo("BANK_UNAVAILABLE");
    assertThat(JsonPath.<String>read(chat.body(), "$.detail")).contains("not available");
    assertThat(confirm.statusCode()).isEqualTo(503);
    assertThat(model.prompts).isEmpty();
  }
}
