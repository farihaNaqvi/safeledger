package com.safeledger.bankmcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.safeledger.bankmcpserver.identity.CallerIdentity;
import com.safeledger.bankmcpserver.identity.IdentityToken;
import io.modelcontextprotocol.client.McpSyncClient;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Identity comes only from the signed header. Bad or missing headers are rejected before any tool
 * runs, and nothing in tool arguments can change who the caller is.
 */
class IdentityIT extends McpIntegrationTestBase {

  private static final String INITIALIZE =
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
      "capabilities":{},"clientInfo":{"name":"raw","version":"1"}}}""";

  @Test
  void mcpRequestWithoutIdentityIsRejectedBeforeAnyTool() {
    HttpResult result = rawMcp(null);

    assertThat(result.status()).isEqualTo(401);
    assertThat(result.body()).contains("invalid_identity");
  }

  @Test
  void forgedExpiredAndTamperedIdentitiesAreRejected() {
    UUID customer = newCustomer();
    String wrongSecret =
        IdentityToken.sign(
            new CallerIdentity(customer, newSession()),
            Instant.now(),
            "not-the-server-secret-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8));
    String expired =
        IdentityToken.sign(
            new CallerIdentity(customer, newSession()),
            Instant.now().minusSeconds(3600),
            SECRET.getBytes(StandardCharsets.UTF_8));
    String valid = token(customer, newSession());
    String tampered = "x" + valid.substring(1);

    for (String token : List.of(wrongSecret, expired, tampered, "garbage")) {
      assertThat(rawMcp(token).status()).as(token).isEqualTo(401);
    }
    assertThat(rawMcp(valid).status()).isEqualTo(200);
  }

  @Test
  void internalEndpointsRequireIdentityToo() {
    UUID someAction = UUID.randomUUID();

    assertThat(postInternal("/internal/actions/" + someAction + "/confirm", null).status())
        .isEqualTo(401);
    assertThat(postInternal("/internal/actions/" + someAction + "/cancel", null).status())
        .isEqualTo(401);
    assertThat(
            send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/internal/actions"))
                    .GET())
                .status())
        .isEqualTo(401);
  }

  @Test
  void rejectionsAreAudited() {
    Integer before =
        jdbc.queryForObject(
            "select count(*) from audit_event where event_type = 'IDENTITY_REJECTED'",
            Integer.class);

    rawMcp("garbage");

    Integer after =
        jdbc.queryForObject(
            "select count(*) from audit_event where event_type = 'IDENTITY_REJECTED'",
            Integer.class);
    assertThat(after).isEqualTo(before + 1);
  }

  @Test
  void customerIdInToolArgumentsIsIgnored() {
    UUID me = newCustomer();
    UUID victim = newCustomer();
    FakeBank.CustomerData mine = bank.customer(me);
    FakeBank.CustomerData theirs = bank.customer(victim);
    McpSyncClient client = connect(me, newSession());

    ToolResult result =
        call(
            client,
            "list_accounts",
            Map.of("customerId", victim.toString(), "customer_id", victim.toString()));

    assertThat(result.isError()).isFalse();
    List<String> ids = result.json("$.items[*].id");
    assertThat(ids)
        .containsExactlyInAnyOrder(mine.savings.toString(), mine.current.toString())
        .doesNotContain(theirs.savings.toString(), theirs.current.toString());
  }

  @Test
  void anotherCustomersAccountIsNotFound() {
    UUID me = newCustomer();
    FakeBank.CustomerData theirs = bank.customer(newCustomer());

    ToolResult result =
        call(
            connect(me, newSession()),
            "get_balance",
            Map.of("accountId", theirs.savings.toString()));

    assertThat(result.isError()).isTrue();
    assertThat(result.<String>json("$.error")).isEqualTo("NOT_FOUND");
    assertThat(result.text()).doesNotContain("250000");
  }

  @Test
  void cannotConfirmAnotherCustomersAction() {
    UUID owner = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(owner);
    ToolResult created =
        call(
            connect(owner, session),
            "request_card_freeze",
            Map.of("cardId", data.activeCard.toString(), "reason", "Lost it"));
    String actionId = created.json("$.actionId");

    HttpResult attempt = confirm(newCustomer(), newSession(), actionId);

    assertThat(attempt.status()).isEqualTo(404);
    assertThat(actionStatus(actionId)).isEqualTo("PENDING");
    assertThat(bank.writesFor(owner)).isEmpty();
  }

  private HttpResult rawMcp(String token) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE));
    if (token != null) {
      request.header(IdentityToken.HEADER, token);
    }
    return send(request);
  }
}
