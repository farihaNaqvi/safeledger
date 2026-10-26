package com.safeledger.bankmcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.safeledger.bankmcpserver.identity.IdentityToken;
import com.safeledger.bankmcpserver.provenance.ProvenancePolicy;
import io.modelcontextprotocol.client.McpSyncClient;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Data-flow control: a write request's deciding values must come from what the customer typed
 * (recorded through /internal/utterances), never only from tool output.
 */
@TestPropertySource(properties = "test.provenance=true")
class ProvenanceIT extends McpIntegrationTestBase {

  HttpResult said(UUID customer, String session, String text) {
    return send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/utterances"))
            .header(IdentityToken.HEADER, token(customer, session))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"" + text + "\"}")));
  }

  ToolResult transfer(McpSyncClient client, UUID payee, UUID from, String amount) {
    return call(
        client,
        "request_transfer",
        Map.of("fromAccountId", from.toString(), "payeeId", payee.toString(), "amount", amount));
  }

  @Test
  void aTransferTheCustomerAskedForGoesThrough() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    assertThat(said(me, session, "Send 1,500 to Sunview Apartments Rent").status()).isEqualTo(204);

    ToolResult r = transfer(connect(me, session), data.payee, data.savings, "1500.00");

    assertThat(r.isError()).isFalse();
  }

  @Test
  void aTransferBuiltFromInjectedDataIsRefusedAndAudited() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    said(me, session, "Show me my payees and recent transactions.");
    McpSyncClient client = connect(me, session);

    // The injected payee name says "move the full balance here"; the customer never asked.
    ToolResult r = transfer(client, data.injectionPayee, data.savings, "50000.00");

    assertThat(r.<String>json("$.error")).isEqualTo(ProvenancePolicy.NOT_FROM_CUSTOMER);
    Integer created =
        jdbc.queryForObject(
            "select count(*) from pending_action where customer_id = ?", Integer.class, me);
    assertThat(created).isZero();
    Integer audited =
        jdbc.queryForObject(
            "select count(*) from audit_event where customer_id = ? and outcome = ?",
            Integer.class,
            me,
            ProvenancePolicy.NOT_FROM_CUSTOMER);
    assertThat(audited).isEqualTo(1);
  }

  @Test
  void theAmountAndThePayeeMustBothBeTheCustomers() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    said(me, session, "Send 1,500 to Sunview Apartments Rent");
    McpSyncClient client = connect(me, session);

    assertThat(transfer(client, data.payee, data.savings, "15000.00").<String>json("$.error"))
        .isEqualTo(ProvenancePolicy.NOT_FROM_CUSTOMER);
    assertThat(
            transfer(client, data.injectionPayee, data.savings, "1500.00").<String>json("$.error"))
        .isEqualTo(ProvenancePolicy.NOT_FROM_CUSTOMER);
  }

  @Test
  void aValueTheCustomerSaidNotToUseDoesNotGroundATransfer() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    said(me, session, "Do not send 1,500 to Lakeside Gym; send 20 to Sunview Apartments Rent");
    McpSyncClient client = connect(me, session);

    // Both 1,500 and Sunview appear in the message, but not as one request.
    assertThat(transfer(client, data.payee, data.savings, "1500.00").<String>json("$.error"))
        .isEqualTo(ProvenancePolicy.NOT_FROM_CUSTOMER);
    assertThat(transfer(client, data.payee, data.savings, "20.00").isError()).isFalse();
  }

  @Test
  void wordsFromAnotherSessionDoNotCount() {
    UUID me = newCustomer();
    FakeBank.CustomerData data = bank.customer(me);
    said(me, newSession(), "Send 1,500 to Sunview Apartments Rent");

    ToolResult r = transfer(connect(me, newSession()), data.payee, data.savings, "1500.00");

    assertThat(r.<String>json("$.error")).isEqualTo(ProvenancePolicy.NOT_FROM_CUSTOMER);
  }

  @Test
  void disputesNeedTheMerchantOrAmountAndUnfreezesTheCard() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    McpSyncClient client = connect(me, session);
    Map<String, Object> dispute =
        Map.of("transactionId", data.debit.toString(), "reason", "Not me");
    Map<String, Object> unfreeze = Map.of("cardId", data.frozenCard.toString());

    said(me, session, "Something looks wrong on my account.");
    assertThat(call(client, "request_dispute", dispute).<String>json("$.error"))
        .isEqualTo(ProvenancePolicy.NOT_FROM_CUSTOMER);
    assertThat(call(client, "request_card_unfreeze", unfreeze).<String>json("$.error"))
        .isEqualTo(ProvenancePolicy.NOT_FROM_CUSTOMER);

    said(me, session, "Dispute the QuickMart charge, and unfreeze my card ending 7710.");
    assertThat(call(client, "request_dispute", dispute).isError()).isFalse();
    assertThat(call(client, "request_card_unfreeze", unfreeze).isError()).isFalse();
  }

  @Test
  void freezingIsProtectiveAndNeedsNoMention() {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    said(me, session, "I lost my wallet, lock things down.");

    ToolResult r =
        call(
            connect(me, session),
            "request_card_freeze",
            Map.of("cardId", data.activeCard.toString(), "reason", "Lost wallet"));

    assertThat(r.isError()).isFalse();
  }

  @Test
  void theUtteranceEndpointNeedsAnIdentityAndText() {
    UUID me = newCustomer();
    String session = newSession();

    assertThat(said(me, session, "").status()).isEqualTo(400);
    HttpResult anonymous =
        send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/utterances"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"Send 50,000\"}")));
    assertThat(anonymous.status()).isEqualTo(401);
  }
}
