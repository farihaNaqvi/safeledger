package com.safeledger.mockbankapi;

import static com.safeledger.mockbankapi.SeedData.ACCOUNT_1;
import static com.safeledger.mockbankapi.SeedData.ACCOUNT_2;
import static com.safeledger.mockbankapi.SeedData.CARD_3;
import static com.safeledger.mockbankapi.SeedData.CARD_5;
import static com.safeledger.mockbankapi.SeedData.CUSTOMER_1;
import static com.safeledger.mockbankapi.SeedData.CUSTOMER_2;
import static com.safeledger.mockbankapi.SeedData.CUSTOMER_3;
import static com.safeledger.mockbankapi.SeedData.PAYEE_INJECTION;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AccountCardPayeeIT extends IntegrationTestBase {

  @Test
  void listsOnlyTheCallersAccounts() {
    Response response = get("/customers/" + CUSTOMER_1 + "/accounts", CUSTOMER_1);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.<List<String>>json("$[*].id"))
        .containsExactly(ACCOUNT_1.toString(), ACCOUNT_2.toString());
    assertThat(response.<List<String>>json("$[*].currency")).containsOnly("INR");
  }

  @Test
  void returnsTheBalance() {
    Response response = get("/accounts/" + ACCOUNT_1 + "/balance", CUSTOMER_1);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.<String>json("$.accountId")).isEqualTo(ACCOUNT_1.toString());
    assertThat(response.<String>json("$.currency")).isEqualTo("INR");
    assertThat(response.decimal("$.balance")).isEqualByComparingTo(balanceOf(ACCOUNT_1));
  }

  @Test
  void listsCardsWithLast4Only() {
    Response response = get("/customers/" + CUSTOMER_1 + "/cards", CUSTOMER_1);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.<List<String>>json("$[*].last4"))
        .hasSize(2)
        .allMatch(l -> l.matches("\\d{4}"));
    assertThat(response.body()).doesNotContainPattern("\\d{12,19}");
  }

  @Test
  void freezeAndUnfreezeAreIdempotent() {
    String path = "/cards/" + CARD_3;

    Response frozen = post(path + "/freeze", CUSTOMER_2, null, null);
    Response frozenAgain = post(path + "/freeze", CUSTOMER_2, null, null);
    Response active = post(path + "/unfreeze", CUSTOMER_2, null, null);
    Response activeAgain = post(path + "/unfreeze", CUSTOMER_2, null, null);

    assertThat(frozen.status()).isEqualTo(200);
    assertThat(frozen.<String>json("$.status")).isEqualTo("FROZEN");
    assertThat(frozenAgain.status()).isEqualTo(200);
    assertThat(frozenAgain.<String>json("$.status")).isEqualTo("FROZEN");
    assertThat(active.<String>json("$.status")).isEqualTo("ACTIVE");
    assertThat(activeAgain.status()).isEqualTo(200);
    assertThat(activeAgain.<String>json("$.status")).isEqualTo("ACTIVE");
  }

  @Test
  void blockedCardCannotBeChanged() {
    Response freeze = post("/cards/" + CARD_5 + "/freeze", CUSTOMER_3, null, null);
    Response unfreeze = post("/cards/" + CARD_5 + "/unfreeze", CUSTOMER_3, null, null);

    assertThat(freeze.status()).isEqualTo(409);
    assertThat(freeze.<String>json("$.code")).isEqualTo("CARD_BLOCKED");
    assertThat(unfreeze.status()).isEqualTo(409);
    assertThat(jdbc.queryForObject("select status from card where id = ?", String.class, CARD_5))
        .isEqualTo("BLOCKED");
  }

  @Test
  void listsPayeesWithMaskedAccountsOnly() {
    Response response = get("/customers/" + CUSTOMER_1 + "/payees", CUSTOMER_1);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.<List<String>>json("$[*].id"))
        .hasSize(3)
        .contains(PAYEE_INJECTION.toString());
    assertThat(response.<List<String>>json("$[*].maskedAccount"))
        .allMatch(m -> m.matches("X+\\d{4}"));
  }
}
