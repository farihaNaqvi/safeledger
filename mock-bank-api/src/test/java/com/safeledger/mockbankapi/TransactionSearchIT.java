package com.safeledger.mockbankapi;

import static com.safeledger.mockbankapi.SeedData.ACCOUNT_1;
import static com.safeledger.mockbankapi.SeedData.CUSTOMER_1;
import static com.safeledger.mockbankapi.SeedData.DEMO_QUICKMART_TXN;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionSearchIT extends IntegrationTestBase {

  private static final String PATH = "/accounts/" + ACCOUNT_1 + "/transactions";

  @Test
  void defaultsToFiftyNewestFirst() {
    Response response = get(PATH, CUSTOMER_1);

    assertThat(response.status()).isEqualTo(200);
    List<String> postedAt = response.json("$[*].postedAt");
    assertThat(postedAt).hasSize(50);
    assertThat(postedAt).isSortedAccordingTo((a, b) -> b.compareTo(a));
  }

  @Test
  void limitIsCapped() {
    assertThat(get(PATH + "?limit=200", CUSTOMER_1).status()).isEqualTo(200);
    assertThat(get(PATH + "?limit=201", CUSTOMER_1).status()).isEqualTo(400);
    assertThat(get(PATH + "?limit=0", CUSTOMER_1).status()).isEqualTo(400);
  }

  @Test
  void filtersByMerchantCaseInsensitively() {
    Response response = get(PATH + "?merchant=quickMART&limit=200", CUSTOMER_1);

    List<String> merchants = response.json("$[*].merchant");
    assertThat(merchants).isNotEmpty().allMatch(m -> m.toLowerCase().contains("quickmart"));
  }

  @Test
  void merchantFilterTreatsWildcardsLiterally() {
    Response response = get(PATH + "?merchant=%25", CUSTOMER_1);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.<List<Object>>json("$")).isEmpty();
  }

  @Test
  void filtersByAmountRange() {
    Response response = get(PATH + "?minAmount=1000&maxAmount=2000&limit=200", CUSTOMER_1);

    List<Object> amounts = response.json("$[*].amount");
    assertThat(amounts)
        .isNotEmpty()
        .allSatisfy(
            a -> {
              BigDecimal amount = new BigDecimal(String.valueOf(a));
              assertThat(amount).isBetween(new BigDecimal("1000"), new BigDecimal("2000"));
            });
  }

  @Test
  void filtersByDateFindingTheDemoTransaction() {
    LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1);
    // The demo row is posted exactly 24h ago, which is "yesterday" or (just after midnight) today.
    String range = "?from=" + yesterday + "&to=" + yesterday.plusDays(1) + "&merchant=QuickMart";

    Response response = get(PATH + range + "&minAmount=4999&maxAmount=4999", CUSTOMER_1);

    assertThat(response.<List<String>>json("$[*].id")).contains(DEMO_QUICKMART_TXN.toString());
  }

  @Test
  void rejectsInvalidRanges() {
    assertThat(get(PATH + "?from=2026-02-01&to=2026-01-01", CUSTOMER_1).status()).isEqualTo(400);
    assertThat(get(PATH + "?minAmount=10&maxAmount=1", CUSTOMER_1).status()).isEqualTo(400);
    assertThat(get(PATH + "?from=yesterday", CUSTOMER_1).status()).isEqualTo(400);
  }
}
