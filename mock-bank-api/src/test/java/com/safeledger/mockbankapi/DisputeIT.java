package com.safeledger.mockbankapi;

import static com.safeledger.mockbankapi.SeedData.ACCOUNT_2;
import static com.safeledger.mockbankapi.SeedData.CUSTOMER_1;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class DisputeIT extends IntegrationTestBase {

  @Test
  void createsAnOpenDisputeThatAppearsInTheList() {
    UUID txn = undisputedTransaction();

    Response created =
        post("/disputes", CUSTOMER_1, newKey(), disputeJson(txn, "I did not make this purchase"));

    assertThat(created.status()).isEqualTo(201);
    assertThat(created.header("Idempotent-Replayed")).isEqualTo("false");
    assertThat(created.<String>json("$.status")).isEqualTo("OPEN");
    assertThat(created.<String>json("$.transactionId")).isEqualTo(txn.toString());
    List<String> listed = get("/customers/" + CUSTOMER_1 + "/disputes", CUSTOMER_1).json("$[*].id");
    assertThat(listed).contains(created.<String>json("$.id"));
  }

  @Test
  void sameKeyAndBodyReplays() {
    UUID txn = undisputedTransaction();
    String key = newKey();
    String body = disputeJson(txn, "Charged twice");

    Response first = post("/disputes", CUSTOMER_1, key, body);
    Response second = post("/disputes", CUSTOMER_1, key, body);

    assertThat(first.status()).isEqualTo(201);
    assertThat(second.status()).isEqualTo(200);
    assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(second.<String>json("$.id")).isEqualTo(first.<String>json("$.id"));
    assertThat(count("select count(*) from dispute where idempotency_key = ?", key)).isEqualTo(1);
  }

  @Test
  void sameKeyWithDifferentBodyIsRejected() {
    UUID txn = undisputedTransaction();
    String key = newKey();
    post("/disputes", CUSTOMER_1, key, disputeJson(txn, "Charged twice"));

    Response reused = post("/disputes", CUSTOMER_1, key, disputeJson(txn, "Different reason"));

    assertThat(reused.status()).isEqualTo(422);
    assertThat(reused.<String>json("$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void secondOpenDisputeOnTheSameTransactionIsRejected() {
    UUID txn = undisputedTransaction();
    post("/disputes", CUSTOMER_1, newKey(), disputeJson(txn, "First"));

    Response second = post("/disputes", CUSTOMER_1, newKey(), disputeJson(txn, "Second"));

    assertThat(second.status()).isEqualTo(409);
    assertThat(second.<String>json("$.code")).isEqualTo("DISPUTE_ALREADY_OPEN");
    assertThat(count("select count(*) from dispute where transaction_id = ?", txn)).isEqualTo(1);
  }

  @Test
  void concurrentRequestsWithTheSameKeyCreateOneDispute() throws Exception {
    UUID txn = undisputedTransaction();
    String key = newKey();
    String body = disputeJson(txn, "Concurrent same key");

    List<Response> responses = concurrently(6, i -> post("/disputes", CUSTOMER_1, key, body));

    assertThat(responses).extracting(Response::status).containsOnly(200, 201);
    assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
    assertThat(count("select count(*) from dispute where transaction_id = ?", txn)).isEqualTo(1);
  }

  @Test
  void concurrentRequestsWithDifferentKeysStillOpenOnlyOneDispute() throws Exception {
    UUID txn = undisputedTransaction();

    List<Response> responses =
        concurrently(
            6, i -> post("/disputes", CUSTOMER_1, newKey(), disputeJson(txn, "Attempt " + i)));

    assertThat(responses).extracting(Response::status).containsOnly(201, 409);
    assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
    assertThat(count("select count(*) from dispute where transaction_id = ?", txn)).isEqualTo(1);
  }

  @Test
  void unknownTransactionGets404() {
    Response response =
        post("/disputes", CUSTOMER_1, newKey(), disputeJson(UUID.randomUUID(), "Unknown"));

    assertThat(response.status()).isEqualTo(404);
  }

  @Test
  void reasonIsRequired() {
    Response response =
        post("/disputes", CUSTOMER_1, newKey(), disputeJson(undisputedTransaction(), " "));

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.<String>json("$.code")).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void idempotencyKeyIsRequired() {
    Response response =
        post("/disputes", CUSTOMER_1, null, disputeJson(undisputedTransaction(), "No key"));

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.<String>json("$.code")).isEqualTo("MISSING_IDEMPOTENCY_KEY");
  }

  /** A seeded, non-injection transaction of customer 1 that has never been disputed. */
  private UUID undisputedTransaction() {
    return jdbc.queryForObject(
        "select t.id from bank_transaction t where t.account_id = ? and t.category <> 'TRANSFER'"
            + " and t.id::text not like '55555555%'"
            + " and not exists (select 1 from dispute d where d.transaction_id = t.id)"
            + " order by t.id limit 1",
        UUID.class, ACCOUNT_2);
  }

  private static List<Response> concurrently(int callers, IntFunction<Response> call)
      throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Response>> calls = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      int n = i;
      calls.add(
          () -> {
            start.await();
            return call.apply(n);
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
    return responses;
  }
}
