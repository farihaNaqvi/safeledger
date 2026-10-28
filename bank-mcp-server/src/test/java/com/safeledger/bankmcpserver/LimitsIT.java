package com.safeledger.bankmcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    // A second session: in the same one, a new transfer would replace the waiting one.
    String second = transfer(connect(me, newSession()), data, "45000.00").json("$.actionId");
    assertThat(actionStatus(second)).isEqualTo("PENDING");

    ToolResult third = transfer(client, data, "10000.01");

    assertThat(third.<String>json("$.error")).isEqualTo("DAILY_TRANSFER_LIMIT");
    assertThat(third.<String>json("$.message")).contains("INR 10000.00 is left");
  }

  @Test
  void aCorrectionIsNotCountedTwiceAgainstTheLimits() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    McpSyncClient client = connect(me, session);
    String done = transfer(client, data, "45000.00").json("$.actionId");
    confirm(me, session, done);
    String waiting = transfer(client, data, "45000.00").json("$.actionId");
    transfer(connect(me, newSession()), data, "1.00");

    // 45,000 executed + 50,000 is within the day; the 45,000 it replaces is not added on top,
    // and the pending count stays at two.
    ToolResult corrected = transfer(client, data, "50000.00");

    assertThat(corrected.isError()).as(corrected.text()).isFalse();
    assertThat(actionStatus(waiting)).isEqualTo("SUPERSEDED");
    assertThat(transfer(connect(me, newSession()), data, "4999.00").<String>json("$.error"))
        .isEqualTo("TOO_MANY_PENDING");
  }

  /**
   * The old card's Confirm and the correction race, each in its own transaction. Either Confirm
   * wins and the correction is checked with the original counted, or the correction wins and the
   * old Confirm is refused. Never both executable over the daily limit.
   */
  @Test
  void aConfirmRacingItsCorrectionNeverLeavesTwoTransfersOverTheLimit() throws Exception {
    int confirmWon = 0;
    int correctionWon = 0;
    for (int round = 0; round < 40; round++) {
      // Each round fires Confirm a little later, so the rounds sweep the whole correction call.
      long delayMs = round * 3L;
      UUID me = newCustomer();
      String session = newSession();
      FakeBank.CustomerData data = bank.customer(me);
      McpSyncClient client = connect(me, session);
      confirm(me, session, transfer(client, data, "30000.00").json("$.actionId"));
      String original = transfer(client, data, "40000.00").json("$.actionId");
      CountDownLatch start = new CountDownLatch(1);

      HttpResult oldConfirm;
      ToolResult correction;
      try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        Future<HttpResult> c =
            pool.submit(
                () -> {
                  start.await();
                  Thread.sleep(delayMs);
                  return confirm(me, session, original);
                });
        Future<ToolResult> r =
            pool.submit(
                () -> {
                  start.await();
                  return transfer(client, data, "45000.00");
                });
        start.countDown();
        oldConfirm = c.get();
        correction = r.get();
      }

      // 30,000 + 40,000 + 45,000 is over the 100,000 day.
      if (oldConfirm.status() == 200) {
        confirmWon++;
        assertThat(actionStatus(original)).isEqualTo("EXECUTED");
        assertThat(correction.<String>json("$.error")).isEqualTo("DAILY_TRANSFER_LIMIT");
      } else {
        correctionWon++;
        assertThat(oldConfirm.<String>json("$.code")).isEqualTo("ACTION_SUPERSEDED");
        assertThat(actionStatus(original)).isEqualTo("SUPERSEDED");
        assertThat(correction.isError()).as(correction.text()).isFalse();
        assertThat(correction.<List<String>>json("$.replaced_requests")).hasSize(1);
      }
      assertThat(bank.writesFor(me)).hasSize(oldConfirm.status() == 200 ? 2 : 1);
    }
    assertThat(confirmWon).as("rounds Confirm won").isPositive();
    assertThat(correctionWon).as("rounds the correction won").isPositive();
  }

  @Test
  void maxPendingActionsPerCustomerAcrossSessions() {
    UUID me = newCustomer();
    FakeBank.CustomerData data = bank.customer(me);
    McpSyncClient sessionA = connect(me, newSession());
    McpSyncClient sessionB = connect(me, newSession());
    String card = data.activeCard.toString();

    assertThat(transfer(sessionA, data, "10.00").isError()).isFalse();
    assertThat(
            call(sessionA, "request_card_freeze", Map.of("cardId", card, "reason", "Lost"))
                .isError())
        .isFalse();
    ToolResult third = transfer(sessionB, data, "30.00");

    assertThat(third.<String>json("$.error")).isEqualTo("TOO_MANY_PENDING");
  }

  @Test
  void anIdenticalRequestWhileOneIsPendingIsRejected() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    McpSyncClient sessionA = connect(me, session);
    McpSyncClient sessionB = connect(me, newSession());
    String card = data.activeCard.toString();

    ToolResult first =
        call(sessionA, "request_card_freeze", Map.of("cardId", card, "reason", "Lost"));
    ToolResult reworded =
        call(sessionA, "request_card_freeze", Map.of("cardId", card, "reason", "I lost it"));
    ToolResult otherSession =
        call(sessionB, "request_card_freeze", Map.of("cardId", card, "reason", "Lost"));
    String transfer = transfer(sessionA, data, "10.00").json("$.actionId");
    ToolResult sameTransfer = transfer(sessionA, data, "10.00");

    assertThat(first.isError()).isFalse();
    assertThat(reworded.<String>json("$.error")).isEqualTo("ALREADY_PENDING");
    assertThat(otherSession.<String>json("$.error")).isEqualTo("ALREADY_PENDING");
    assertThat(sameTransfer.<String>json("$.error")).isEqualTo("ALREADY_PENDING");
    assertThat(actionStatus(transfer)).isEqualTo("PENDING");

    // Once the first one is cancelled, asking again is allowed.
    cancel(me, session, first.json("$.actionId"));
    assertThat(
            call(sessionB, "request_card_freeze", Map.of("cardId", card, "reason", "Lost"))
                .isError())
        .isFalse();
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
