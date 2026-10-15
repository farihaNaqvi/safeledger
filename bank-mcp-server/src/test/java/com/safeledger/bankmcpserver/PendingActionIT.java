package com.safeledger.bankmcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Write tools only create pending actions; the bank changes only after a confirm through the
 * internal endpoint, and then exactly once.
 */
class PendingActionIT extends McpIntegrationTestBase {

  @Test
  void writeToolCreatesAPendingActionAndTouchesNothing() {
    UUID me = newCustomer();
    FakeBank.CustomerData data = bank.customer(me);

    ToolResult result =
        call(
            connect(me, newSession()),
            "request_transfer",
            Map.of(
                "fromAccountId",
                data.savings.toString(),
                "payeeId",
                data.payee.toString(),
                "amount",
                "1500.00",
                "note",
                "October rent"));

    assertThat(result.isError()).isFalse();
    assertThat(result.<String>json("$.status")).isEqualTo("PENDING_CUSTOMER_CONFIRMATION");
    assertThat(result.<String>json("$.summary"))
        .contains("INR 1500.00", "Sunview Apartments Rent", "October rent");
    assertThat(result.<String>json("$.next_step")).contains("You cannot confirm");
    assertThat(actionStatus(result.json("$.actionId"))).isEqualTo("PENDING");
    assertThat(bank.writesFor(me)).isEmpty();
    assertThat(data.accounts.get(data.savings).balance()).isEqualByComparingTo("250000.00");
  }

  @Test
  void confirmExecutesOnceWithTheStoredIdempotencyKey() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    String actionId = transferRequest(me, session, data, "2000.00");

    HttpResult first = confirm(me, session, actionId);
    HttpResult second = confirm(me, session, actionId);

    assertThat(first.status()).isEqualTo(200);
    assertThat(first.<String>json("$.status")).isEqualTo("EXECUTED");
    assertThat(first.<Boolean>json("$.result.ok")).isTrue();
    assertThat(second.status()).isEqualTo(409);
    assertThat(second.<String>json("$.code")).isEqualTo("ACTION_NOT_PENDING");
    assertThat(bank.writesFor(me)).hasSize(1);
    String storedKey =
        jdbc.queryForObject(
            "select idempotency_key from pending_action where id = ?::uuid",
            String.class,
            actionId);
    assertThat(bank.writesFor(me).getFirst().idempotencyKey()).isEqualTo(storedKey);
    assertThat(data.accounts.get(data.savings).balance()).isEqualByComparingTo("248000.00");
    assertThat(audit("ACTION_CONFIRMED", actionId)).isEqualTo(1);
    assertThat(audit("ACTION_EXECUTED", actionId)).isEqualTo(1);
    assertThat(audit("ACTION_REJECTED", actionId)).isEqualTo(1);
  }

  @Test
  void concurrentConfirmsExecuteExactlyOnce() throws Exception {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    String actionId = transferRequest(me, session, data, "300.00");
    int callers = 8;
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<HttpResult>> calls = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      calls.add(
          () -> {
            start.await();
            return confirm(me, session, actionId);
          });
    }

    List<HttpResult> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
      List<Future<HttpResult>> futures = calls.stream().map(pool::submit).toList();
      start.countDown();
      for (Future<HttpResult> f : futures) {
        results.add(f.get());
      }
    }

    assertThat(results).filteredOn(r -> r.status() == 200).hasSize(1);
    assertThat(results).filteredOn(r -> r.status() == 409).hasSize(callers - 1);
    assertThat(bank.writesFor(me)).hasSize(1);
    assertThat(data.accounts.get(data.savings).balance()).isEqualByComparingTo("249700.00");
  }

  @Test
  void expiredActionCannotBeConfirmed() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    String actionId = transferRequest(me, session, data, "100.00");
    backdate(actionId);

    HttpResult result = confirm(me, session, actionId);

    assertThat(result.status()).isEqualTo(410);
    assertThat(result.<String>json("$.code")).isEqualTo("ACTION_EXPIRED");
    assertThat(actionStatus(actionId)).isEqualTo("EXPIRED");
    assertThat(bank.writesFor(me)).isEmpty();
    assertThat(audit("ACTION_EXPIRED", actionId)).isEqualTo(1);
  }

  @Test
  void expirySweepMarksOverdueActions() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    String overdue = transferRequest(me, session, data, "100.00");
    String fresh = transferRequest(me, session, data, "200.00");
    backdate(overdue);

    expirySweep();

    assertThat(actionStatus(overdue)).isEqualTo("EXPIRED");
    assertThat(actionStatus(fresh)).isEqualTo("PENDING");
    assertThat(audit("ACTION_EXPIRED", overdue)).isEqualTo(1);
  }

  @Test
  void cancelledActionCannotBeConfirmed() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    String actionId = transferRequest(me, session, data, "100.00");

    HttpResult cancelled = cancel(me, session, actionId);
    HttpResult confirmAfter = confirm(me, session, actionId);
    HttpResult cancelAgain = cancel(me, session, actionId);

    assertThat(cancelled.status()).isEqualTo(200);
    assertThat(cancelled.<String>json("$.status")).isEqualTo("CANCELLED");
    assertThat(confirmAfter.status()).isEqualTo(409);
    assertThat(cancelAgain.status()).isEqualTo(409);
    assertThat(bank.writesFor(me)).isEmpty();
  }

  @Test
  void bankRejectionAtConfirmTimeMarksTheActionFailed() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    String actionId = transferRequest(me, session, data, "5000.00");
    bank.setBalance(me, data.savings, new BigDecimal("10.00"));

    HttpResult result = confirm(me, session, actionId);

    assertThat(result.status()).isEqualTo(200);
    assertThat(result.<String>json("$.status")).isEqualTo("FAILED");
    assertThat(result.<String>json("$.result.code")).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(audit("ACTION_FAILED", actionId)).isEqualTo(1);
  }

  @Test
  void freezeDisputeAndUnfreezeRunThroughTheSameFlow() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    McpSyncClient client = connect(me, session);

    ToolResult freeze =
        call(
            client,
            "request_card_freeze",
            Map.of("cardId", data.activeCard.toString(), "reason", "Lost"));
    ToolResult dispute =
        call(
            client,
            "request_dispute",
            Map.of("transactionId", data.debit.toString(), "reason", "Not me"));
    assertThat(confirm(me, session, freeze.json("$.actionId")).status()).isEqualTo(200);
    assertThat(confirm(me, session, dispute.json("$.actionId")).status()).isEqualTo(200);
    ToolResult unfreeze =
        call(client, "request_card_unfreeze", Map.of("cardId", data.activeCard.toString()));
    assertThat(confirm(me, session, unfreeze.json("$.actionId")).status()).isEqualTo(200);

    assertThat(bank.writesFor(me))
        .extracting(FakeBank.WriteCall::endpoint)
        .containsExactly("freeze", "dispute", "unfreeze");
    assertThat(data.cards.get(data.activeCard).status()).isEqualTo("ACTIVE");
    assertThat(data.disputes).hasSize(1);
  }

  @Test
  void writeToolsValidateAgainstTheBankBeforeCreatingAnything() {
    UUID me = newCustomer();
    FakeBank.CustomerData data = bank.customer(me);
    FakeBank.CustomerData other = bank.customer(newCustomer());
    McpSyncClient client = connect(me, newSession());
    // Six write attempts in total; the session limit is five (rejected ones count), so the
    // transfer checks use a second session.
    McpSyncClient transfers = connect(me, newSession());

    assertThat(
            call(
                    client,
                    "request_card_freeze",
                    Map.of("cardId", data.blockedCard.toString(), "reason", "x"))
                .<String>json("$.error"))
        .isEqualTo("CARD_BLOCKED");
    assertThat(
            call(
                    client,
                    "request_card_freeze",
                    Map.of("cardId", data.frozenCard.toString(), "reason", "x"))
                .<String>json("$.error"))
        .isEqualTo("ALREADY_FROZEN");
    assertThat(
            call(
                    client,
                    "request_card_freeze",
                    Map.of("cardId", other.activeCard.toString(), "reason", "x"))
                .<String>json("$.error"))
        .isEqualTo("CARD_NOT_FOUND");
    assertThat(
            call(
                    client,
                    "request_dispute",
                    Map.of("transactionId", data.credit.toString(), "reason", "x"))
                .<String>json("$.error"))
        .isEqualTo("NOT_DISPUTABLE");
    assertThat(
            call(
                    transfers,
                    "request_transfer",
                    Map.of(
                        "fromAccountId", data.savings.toString(),
                        "payeeId", other.payee.toString(),
                        "amount", "10.00"))
                .<String>json("$.error"))
        .isEqualTo("PAYEE_NOT_FOUND");
    assertThat(
            call(
                    transfers,
                    "request_transfer",
                    Map.of(
                        "fromAccountId", data.current.toString(),
                        "payeeId", data.payee.toString(),
                        "amount", "40000.00"))
                .<String>json("$.error"))
        .isEqualTo("INSUFFICIENT_FUNDS");
    Integer created =
        jdbc.queryForObject(
            "select count(*) from pending_action where customer_id = ?", Integer.class, me);
    assertThat(created).isZero();
  }

  @Test
  void listsTheSessionsActions() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    String actionId = transferRequest(me, session, data, "100.00");

    HttpResult result =
        send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://localhost:" + port + "/internal/actions"))
                .header(
                    com.safeledger.bankmcpserver.identity.IdentityToken.HEADER, token(me, session))
                .GET());

    assertThat(result.status()).isEqualTo(200);
    assertThat(result.<List<String>>json("$[*].id")).containsExactly(actionId);
  }

  private String transferRequest(
      UUID customer, String session, FakeBank.CustomerData data, String amount) {
    ToolResult result =
        call(
            connect(customer, session),
            "request_transfer",
            Map.of(
                "fromAccountId", data.savings.toString(),
                "payeeId", data.payee.toString(),
                "amount", amount));
    assertThat(result.isError()).as(result.text()).isFalse();
    return result.json("$.actionId");
  }

  private void backdate(String actionId) {
    jdbc.update(
        "update pending_action set expires_at = now() - interval '1 second' where id = ?::uuid",
        actionId);
  }

  @org.springframework.beans.factory.annotation.Autowired
  com.safeledger.bankmcpserver.action.ActionExpiryJob expiryJob;

  private void expirySweep() {
    expiryJob.sweep();
  }
}
