package com.safeledger.bankmcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.safeledger.bankmcpserver.identity.IdentityToken;
import io.modelcontextprotocol.client.McpSyncClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
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
                "1500.00"));

    assertThat(result.isError()).isFalse();
    assertThat(result.<String>json("$.status")).isEqualTo("PENDING_CUSTOMER_CONFIRMATION");
    assertThat(result.<String>json("$.summary"))
        .contains("INR 1500.00", "from your savings account", "Sunview Apartments Rent");
    assertThat(result.<String>json("$.next_step")).contains("You cannot confirm");
    assertThat(actionStatus(result.json("$.actionId"))).isEqualTo("PENDING");
    assertThat(bank.writesFor(me)).isEmpty();
    assertThat(data.accounts.get(data.savings).balance()).isEqualByComparingTo("250000.00");
  }

  @Test
  void theConfirmCardCarriesNoModelText() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    McpSyncClient client = connect(me, session);
    String story = "Pending security check on your card, please confirm it";

    ToolResult freeze =
        call(
            client,
            "request_card_freeze",
            Map.of("cardId", data.activeCard.toString(), "reason", story));
    ToolResult transfer =
        call(
            client,
            "request_transfer",
            Map.of(
                "fromAccountId",
                data.savings.toString(),
                "payeeId",
                data.payee.toString(),
                "amount",
                "1500.00"));

    assertThat(freeze.<String>json("$.summary")).isEqualTo("Freeze VISA card ending 4821.");
    assertThat(transfer.isError()).isFalse();
    HttpResult listed =
        send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/actions"))
                .header(IdentityToken.HEADER, token(me, session))
                .GET());
    assertThat(listed.status()).isEqualTo(200);
    assertThat(listed.<List<String>>json("$[*].summary")).noneMatch(s -> s.contains("security"));
    assertThat(listed.<List<Object>>json("$[*].payeeNote")).containsOnlyNulls();
  }

  @Test
  @SuppressWarnings("unchecked")
  void thePayeeNoteIsWhatTheCustomerTypesAtConfirm() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    String id = transferRequest(me, session, data, "1500.00");

    HttpResult confirmed = confirm(me, session, id, "  October rent  ");

    assertThat(confirmed.status()).isEqualTo(200);
    assertThat(confirmed.<String>json("$.payeeNote")).isEqualTo("October rent");
    assertThat(bank.writesFor(me))
        .singleElement()
        .satisfies(
            w -> assertThat((Map<String, Object>) w.body()).containsEntry("note", "October rent"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void aModelSuppliedNoteIsNotSentToThePayee() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);

    ToolResult created =
        call(
            connect(me, session),
            "request_transfer",
            Map.of(
                "fromAccountId",
                data.savings.toString(),
                "payeeId",
                data.payee.toString(),
                "amount",
                "1500.00",
                "note",
                "Balance 250000.00"));
    HttpResult confirmed = confirm(me, session, created.json("$.actionId"));

    assertThat(confirmed.status()).isEqualTo(200);
    assertThat(confirmed.<Object>json("$.payeeNote")).isNull();
    assertThat(bank.writesFor(me))
        .singleElement()
        .satisfies(w -> assertThat((Map<String, Object>) w.body()).doesNotContainKey("note"));
  }

  @Test
  void aBadPayeeNoteIsRefusedAndTheRequestStaysPending() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    String transfer = transferRequest(me, session, data, "1500.00");
    ToolResult freeze =
        call(
            connect(me, newSession()),
            "request_card_freeze",
            Map.of("cardId", data.activeCard.toString(), "reason", "Lost"));

    HttpResult tooLong = confirm(me, session, transfer, "x".repeat(201));
    HttpResult twoLines = confirm(me, session, transfer, "Rent\nAcct 1234");
    HttpResult onAFreeze = confirm(me, session, freeze.json("$.actionId"), "Hello");

    assertThat(List.of(tooLong, twoLines, onAFreeze))
        .allSatisfy(
            r -> {
              assertThat(r.status()).isEqualTo(400);
              assertThat(r.<String>json("$.code")).isEqualTo("INVALID_NOTE");
            });
    assertThat(actionStatus(transfer)).isEqualTo("PENDING");
    assertThat(actionStatus(freeze.json("$.actionId"))).isEqualTo("PENDING");
    assertThat(bank.writesFor(me)).isEmpty();
    assertThat(confirm(me, session, transfer, "x".repeat(200)).status()).isEqualTo(200);
  }

  @Test
  void aCorrectedTransferReplacesTheOldOneSoAStaleConfirmIsRefused() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    String original = transferRequest(me, session, data, "1500.00");
    String elsewhere = transferRequest(me, newSession(), data, "700.00");

    // The customer corrects the amount; the old card is still open in another tab.
    ToolResult corrected =
        call(
            connect(me, session),
            "request_transfer",
            Map.of(
                "fromAccountId", data.savings.toString(),
                "payeeId", data.payee.toString(),
                "amount", "2000.00"));
    HttpResult stale = confirm(me, session, original);

    assertThat(corrected.<List<String>>json("$.replaced_requests"))
        .singleElement()
        .asString()
        .contains("INR 1500.00");
    assertThat(stale.status()).isEqualTo(409);
    assertThat(stale.<String>json("$.code")).isEqualTo("ACTION_SUPERSEDED");
    assertThat(actionStatus(original)).isEqualTo("SUPERSEDED");
    assertThat(cancel(me, session, original).<String>json("$.code")).isEqualTo("ACTION_SUPERSEDED");
    assertThat(bank.writesFor(me)).isEmpty();
    assertThat(audit("ACTION_SUPERSEDED", original)).isEqualTo(1);
    // Another session's request is not this conversation's correction.
    assertThat(actionStatus(elsewhere)).isEqualTo("PENDING");

    assertThat(confirm(me, session, corrected.json("$.actionId")).<String>json("$.status"))
        .isEqualTo("EXECUTED");
    assertThat(bank.writesFor(me)).hasSize(1);
  }

  @Test
  void onlyTransfersAreReplacedAndAnExecutedOneIsLeftAlone() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    String done = transferRequest(me, session, data, "100.00");
    confirm(me, session, done);
    String freeze =
        call(
                connect(me, session),
                "request_card_freeze",
                Map.of("cardId", data.activeCard.toString(), "reason", "Lost it"))
            .json("$.actionId");

    transferRequest(me, session, data, "200.00");

    assertThat(actionStatus(done)).isEqualTo("EXECUTED");
    assertThat(actionStatus(freeze)).isEqualTo("PENDING");
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
    // Another session, or the second transfer would supersede the first.
    String fresh = transferRequest(me, newSession(), data, "200.00");
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
