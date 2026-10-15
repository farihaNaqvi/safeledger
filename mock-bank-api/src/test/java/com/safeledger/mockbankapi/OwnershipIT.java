package com.safeledger.mockbankapi;

import static com.safeledger.mockbankapi.SeedData.ACCOUNT_1;
import static com.safeledger.mockbankapi.SeedData.ACCOUNT_3;
import static com.safeledger.mockbankapi.SeedData.CARD_1;
import static com.safeledger.mockbankapi.SeedData.CUSTOMER_1;
import static com.safeledger.mockbankapi.SeedData.CUSTOMER_2;
import static com.safeledger.mockbankapi.SeedData.DEMO_QUICKMART_TXN;
import static com.safeledger.mockbankapi.SeedData.PAYEE_1;
import static com.safeledger.mockbankapi.SeedData.PAYEE_4;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Spec rule: every endpoint checks the resource belongs to X-Customer-Id, otherwise 404 (not 403),
 * so another customer's resources look exactly like resources that do not exist.
 */
class OwnershipIT extends IntegrationTestBase {

  /** Customer 1's resources, as GET paths. */
  static Stream<String> customer1ReadPaths() {
    return Stream.of(
        "/customers/" + CUSTOMER_1 + "/accounts",
        "/customers/" + CUSTOMER_1 + "/cards",
        "/customers/" + CUSTOMER_1 + "/payees",
        "/customers/" + CUSTOMER_1 + "/disputes",
        "/accounts/" + ACCOUNT_1 + "/balance",
        "/accounts/" + ACCOUNT_1 + "/transactions");
  }

  @ParameterizedTest
  @MethodSource("customer1ReadPaths")
  void anotherCustomerGets404(String path) {
    assertThat(get(path, CUSTOMER_1).status()).isEqualTo(200);

    Response asOther = get(path, CUSTOMER_2);

    assertThat(asOther.status()).isEqualTo(404);
    assertThat(asOther.<String>json("$.code")).isEqualTo("NOT_FOUND");
    assertThat(asOther.body()).doesNotContain(CUSTOMER_1.toString(), ACCOUNT_1.toString());
  }

  @ParameterizedTest
  @MethodSource("customer1ReadPaths")
  void missingCustomerHeaderGets401(String path) {
    Response response = get(path, null);

    assertThat(response.status()).isEqualTo(401);
    assertThat(response.<String>json("$.code")).isEqualTo("MISSING_CUSTOMER_ID");
  }

  @Test
  void malformedCustomerHeaderGets400() {
    Response response =
        send(
            HttpRequest.newBuilder(uri("/customers/" + CUSTOMER_1 + "/accounts"))
                .header("X-Customer-Id", "not-a-uuid")
                .GET());

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.<String>json("$.code")).isEqualTo("INVALID_PARAMETER");
  }

  @Test
  void otherCustomersAccountLooksTheSameAsAMissingOne() {
    Response otherCustomers = get("/accounts/" + ACCOUNT_3 + "/balance", CUSTOMER_1);
    Response missing = get("/accounts/" + UUID.randomUUID() + "/balance", CUSTOMER_1);

    assertThat(otherCustomers.status()).isEqualTo(404);
    assertThat(missing.status()).isEqualTo(404);
    assertThat(otherCustomers.<String>json("$.detail")).isEqualTo(missing.<String>json("$.detail"));
  }

  @Test
  void cannotFreezeAnotherCustomersCard() {
    String before = cardStatus(CARD_1);

    Response response = post("/cards/" + CARD_1 + "/freeze", CUSTOMER_2, null, null);

    assertThat(response.status()).isEqualTo(404);
    assertThat(cardStatus(CARD_1)).isEqualTo(before);
  }

  @Test
  void cannotUnfreezeAnotherCustomersCard() {
    Response response = post("/cards/" + CARD_1 + "/unfreeze", CUSTOMER_2, null, null);

    assertThat(response.status()).isEqualTo(404);
  }

  @Test
  void cannotTransferFromAnotherCustomersAccount() {
    BigDecimal before = balanceOf(ACCOUNT_1);

    Response response =
        post("/transfers", CUSTOMER_2, newKey(), transferJson(ACCOUNT_1, PAYEE_4, "10.00", null));

    assertThat(response.status()).isEqualTo(404);
    assertThat(balanceOf(ACCOUNT_1)).isEqualByComparingTo(before);
  }

  @Test
  void cannotTransferToAnotherCustomersPayee() {
    BigDecimal before = balanceOf(ACCOUNT_3);

    Response otherCustomersPayee =
        post("/transfers", CUSTOMER_2, newKey(), transferJson(ACCOUNT_3, PAYEE_1, "10.00", null));
    Response missingPayee =
        post(
            "/transfers",
            CUSTOMER_2,
            newKey(),
            transferJson(ACCOUNT_3, UUID.randomUUID(), "10.00", null));

    assertThat(otherCustomersPayee.status()).isEqualTo(422);
    assertThat(otherCustomersPayee.<String>json("$.code")).isEqualTo("UNKNOWN_PAYEE");
    assertThat(otherCustomersPayee.<String>json("$.detail"))
        .isEqualTo(missingPayee.<String>json("$.detail"));
    assertThat(balanceOf(ACCOUNT_3)).isEqualByComparingTo(before);
  }

  @Test
  void cannotDisputeAnotherCustomersTransaction() {
    int disputesBefore = count("select count(*) from dispute");

    Response response =
        post("/disputes", CUSTOMER_2, newKey(), disputeJson(DEMO_QUICKMART_TXN, "Not my purchase"));

    assertThat(response.status()).isEqualTo(404);
    assertThat(count("select count(*) from dispute")).isEqualTo(disputesBefore);
  }

  @Test
  void idempotencyKeysAreScopedPerCustomer() {
    String sharedKey = newKey();
    Response first =
        post("/transfers", CUSTOMER_1, sharedKey, transferJson(ACCOUNT_1, PAYEE_1, "11.00", null));

    Response second =
        post("/transfers", CUSTOMER_2, sharedKey, transferJson(ACCOUNT_3, PAYEE_4, "12.00", null));

    assertThat(first.status()).isEqualTo(201);
    assertThat(second.status()).isEqualTo(201);
    assertThat(second.<String>json("$.id")).isNotEqualTo(first.<String>json("$.id"));
    assertThat(second.body()).doesNotContain(ACCOUNT_1.toString());
  }

  private String cardStatus(UUID cardId) {
    return jdbc.queryForObject("select status from card where id = ?", String.class, cardId);
  }
}
