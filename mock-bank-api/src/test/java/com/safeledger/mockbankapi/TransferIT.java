package com.safeledger.mockbankapi;

import static com.safeledger.mockbankapi.SeedData.ACCOUNT_1;
import static com.safeledger.mockbankapi.SeedData.ACCOUNT_2;
import static com.safeledger.mockbankapi.SeedData.ACCOUNT_5;
import static com.safeledger.mockbankapi.SeedData.CUSTOMER_1;
import static com.safeledger.mockbankapi.SeedData.CUSTOMER_3;
import static com.safeledger.mockbankapi.SeedData.PAYEE_1;
import static com.safeledger.mockbankapi.SeedData.PAYEE_6;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class TransferIT extends IntegrationTestBase {

  @Test
  void transferDebitsTheAccountAndRecordsATransaction() {
    BigDecimal before = balanceOf(ACCOUNT_2);
    String key = newKey();

    Response response =
        post("/transfers", CUSTOMER_1, key, transferJson(ACCOUNT_2, PAYEE_1, "1000.25", "Rent"));

    assertThat(response.status()).isEqualTo(201);
    assertThat(response.header("Idempotent-Replayed")).isEqualTo("false");
    assertThat(response.<String>json("$.status")).isEqualTo("COMPLETED");
    assertThat(response.decimal("$.amount")).isEqualByComparingTo("1000.25");
    assertThat(response.<String>json("$.currency")).isEqualTo("INR");
    assertThat(balanceOf(ACCOUNT_2))
        .isEqualByComparingTo(before.subtract(new BigDecimal("1000.25")));
    assertThat(
            count(
                "select count(*) from bank_transaction where account_id = ? and category ="
                    + " 'TRANSFER' and amount = 1000.25 and description like '%Rent'",
                ACCOUNT_2))
        .isEqualTo(1);
  }

  @Test
  void sameKeyAndBodyReplaysWithoutDebitingAgain() {
    BigDecimal before = balanceOf(ACCOUNT_1);
    String key = newKey();
    String body = transferJson(ACCOUNT_1, PAYEE_1, "250.00", null);

    Response first = post("/transfers", CUSTOMER_1, key, body);
    Response second = post("/transfers", CUSTOMER_1, key, body);
    Response third = post("/transfers", CUSTOMER_1, key, body);

    assertThat(first.status()).isEqualTo(201);
    assertThat(second.status()).isEqualTo(200);
    assertThat(third.status()).isEqualTo(200);
    assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(second.<String>json("$.id")).isEqualTo(first.<String>json("$.id"));
    assertThat(third.<String>json("$.id")).isEqualTo(first.<String>json("$.id"));
    assertThat(balanceOf(ACCOUNT_1))
        .isEqualByComparingTo(before.subtract(new BigDecimal("250.00")));
    assertThat(count("select count(*) from transfer where idempotency_key = ?", key)).isEqualTo(1);
  }

  @Test
  void sameKeyWithDifferentBodyIsRejected() {
    String key = newKey();
    post("/transfers", CUSTOMER_1, key, transferJson(ACCOUNT_1, PAYEE_1, "10.00", null));
    BigDecimal afterFirst = balanceOf(ACCOUNT_1);

    Response reused =
        post("/transfers", CUSTOMER_1, key, transferJson(ACCOUNT_1, PAYEE_1, "9999.00", null));

    assertThat(reused.status()).isEqualTo(422);
    assertThat(reused.<String>json("$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(balanceOf(ACCOUNT_1)).isEqualByComparingTo(afterFirst);
    assertThat(count("select count(*) from transfer where idempotency_key = ?", key)).isEqualTo(1);
  }

  @Test
  void concurrentRequestsWithTheSameKeyDebitExactlyOnce() throws Exception {
    BigDecimal before = balanceOf(ACCOUNT_1);
    String key = newKey();
    String body = transferJson(ACCOUNT_1, PAYEE_1, "77.70", null);
    int callers = 8;
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Response>> calls = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      calls.add(
          () -> {
            start.await();
            return post("/transfers", CUSTOMER_1, key, body);
          });
    }

    List<Response> responses = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
      List<Future<Response>> futures = calls.stream().map(pool::submit).toList();
      start.countDown();
      for (Future<Response> f : futures) {
        responses.add(f.get());
      }
    }

    assertThat(responses).extracting(Response::status).containsOnly(200, 201);
    assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
    assertThat(responses)
        .extracting(r -> r.<String>json("$.id"))
        .containsOnly(responses.getFirst().<String>json("$.id"));
    assertThat(count("select count(*) from transfer where idempotency_key = ?", key)).isEqualTo(1);
    assertThat(balanceOf(ACCOUNT_1)).isEqualByComparingTo(before.subtract(new BigDecimal("77.70")));
  }

  @Test
  void insufficientBalanceFailsCleanly() {
    BigDecimal before = balanceOf(ACCOUNT_5);
    int transactionsBefore =
        count("select count(*) from bank_transaction where account_id = ?", ACCOUNT_5);
    String key = newKey();
    String tooMuch = before.add(new BigDecimal("0.01")).toPlainString();

    Response response =
        post("/transfers", CUSTOMER_3, key, transferJson(ACCOUNT_5, PAYEE_6, tooMuch, null));

    assertThat(response.status()).isEqualTo(422);
    assertThat(response.<String>json("$.code")).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(balanceOf(ACCOUNT_5)).isEqualByComparingTo(before);
    assertThat(count("select count(*) from transfer where idempotency_key = ?", key)).isZero();
    assertThat(count("select count(*) from bank_transaction where account_id = ?", ACCOUNT_5))
        .isEqualTo(transactionsBefore);
  }

  @Test
  void transferOfTheExactBalanceIsAllowed() {
    // Account 5 is only used by this class; the insufficient-funds test reads its balance fresh.
    String exactBalance = balanceOf(ACCOUNT_5).toPlainString();

    Response response =
        post(
            "/transfers",
            CUSTOMER_3,
            newKey(),
            transferJson(ACCOUNT_5, PAYEE_6, exactBalance, null));

    assertThat(response.status()).isEqualTo(201);
    assertThat(balanceOf(ACCOUNT_5)).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void unknownPayeeFailsCleanly() {
    BigDecimal before = balanceOf(ACCOUNT_1);

    Response response =
        post(
            "/transfers",
            CUSTOMER_1,
            newKey(),
            transferJson(ACCOUNT_1, UUID.randomUUID(), "10.00", null));

    assertThat(response.status()).isEqualTo(422);
    assertThat(response.<String>json("$.code")).isEqualTo("UNKNOWN_PAYEE");
    assertThat(balanceOf(ACCOUNT_1)).isEqualByComparingTo(before);
  }

  @Test
  void idempotencyKeyIsRequired() {
    Response response =
        post("/transfers", CUSTOMER_1, null, transferJson(ACCOUNT_1, PAYEE_1, "10.00", null));

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.<String>json("$.code")).isEqualTo("MISSING_IDEMPOTENCY_KEY");
  }

  @Test
  void malformedIdempotencyKeyIsRejected() {
    Response response =
        post(
            "/transfers",
            CUSTOMER_1,
            "has spaces!",
            transferJson(ACCOUNT_1, PAYEE_1, "10.00", null));

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.<String>json("$.code")).isEqualTo("INVALID_IDEMPOTENCY_KEY");
  }

  @Test
  void invalidAmountsAreRejected() {
    for (String amount : List.of("0", "-5.00", "10.001")) {
      Response response =
          post("/transfers", CUSTOMER_1, newKey(), transferJson(ACCOUNT_1, PAYEE_1, amount, null));

      assertThat(response.status()).as("amount %s", amount).isEqualTo(400);
      assertThat(response.<String>json("$.code")).isEqualTo("VALIDATION_FAILED");
    }
  }

  @Test
  void malformedBodyIsRejected() {
    Response response = post("/transfers", CUSTOMER_1, newKey(), "{not json");

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.<String>json("$.code")).isEqualTo("MALFORMED_BODY");
  }
}
