package com.safeledger.bankmcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Defaults from application.yml: 50,000 per transfer, 100,000 per day, 5 writes per session, 2
 * pending per customer, 30 tool calls per minute per session.
 */
class LimitsIT extends McpIntegrationTestBase {

  @Test
  void singleTransferLimit() {
    UUID me = newCustomer();
    FakeBank.CustomerData data = bank.customer(me);

    ToolResult result = transfer(connect(me, newSession()), data, "50000.01");

    assertThat(result.<String>json("$.error")).isEqualTo("TRANSFER_LIMIT");
  }

  @Test
  void dailyLimitCountsPendingTransfersSoSplittingDoesNotHelp() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    McpSyncClient client = connect(me, session);

    String first = transfer(client, data, "45000.00").json("$.actionId");
    assertThat(confirm(me, session, first).status()).isEqualTo(200);
    String second = transfer(client, data, "45000.00").json("$.actionId");
    assertThat(actionStatus(second)).isEqualTo("PENDING");

    ToolResult third = transfer(client, data, "10000.01");

    assertThat(third.<String>json("$.error")).isEqualTo("DAILY_TRANSFER_LIMIT");
    assertThat(third.<String>json("$.message")).contains("INR 10000.00 is left");
  }

  @Test
  void maxPendingActionsPerCustomerAcrossSessions() {
    UUID me = newCustomer();
    FakeBank.CustomerData data = bank.customer(me);
    McpSyncClient sessionA = connect(me, newSession());
    McpSyncClient sessionB = connect(me, newSession());

    assertThat(transfer(sessionA, data, "10.00").isError()).isFalse();
    assertThat(transfer(sessionA, data, "20.00").isError()).isFalse();
    ToolResult third = transfer(sessionB, data, "30.00");

    assertThat(third.<String>json("$.error")).isEqualTo("TOO_MANY_PENDING");
  }

  @Test
  void maxWriteRequestsPerSessionIncludingRejectedOnes() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    McpSyncClient client = connect(me, session);

    for (int i = 0; i < 5; i++) {
      ToolResult r = transfer(client, data, "1.00");
      if (!r.isError()) {
        cancel(me, session, r.json("$.actionId"));
      }
    }
    ToolResult sixth = transfer(client, data, "1.00");

    assertThat(sixth.<String>json("$.error")).isEqualTo("SESSION_WRITE_LIMIT");
    assertThat(transfer(connect(me, newSession()), data, "1.00").isError()).isFalse();
  }

  @Test
  void rateLimitPerSession() {
    UUID me = newCustomer();
    McpSyncClient client = connect(me, newSession());

    for (int i = 0; i < 30; i++) {
      assertThat(call(client, "list_cards", Map.of()).isError()).as("call %s", i + 1).isFalse();
    }
    ToolResult limited = call(client, "list_cards", Map.of());

    assertThat(limited.<String>json("$.error")).isEqualTo("RATE_LIMITED");
    assertThat(call(connect(me, newSession()), "list_cards", Map.of()).isError()).isFalse();
  }

  private static ToolResult transfer(
      McpSyncClient client, FakeBank.CustomerData data, String amount) {
    return call(
        client,
        "request_transfer",
        Map.of(
            "fromAccountId", data.savings.toString(),
            "payeeId", data.payee.toString(),
            "amount", amount));
  }
}
