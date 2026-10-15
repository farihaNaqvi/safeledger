package com.safeledger.mockbankapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApplicationIT extends IntegrationTestBase {

  @Test
  void healthIsUp() {
    Response response = get("/actuator/health", null);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.<String>json("$.status")).isEqualTo("UP");
  }

  @Test
  void echoesTheCallersCorrelationId() {
    Response response =
        send(
            HttpRequest.newBuilder(uri("/customers/" + SeedData.CUSTOMER_1 + "/accounts"))
                .header("X-Customer-Id", SeedData.CUSTOMER_1.toString())
                .header("X-Correlation-Id", "corr-test-123")
                .GET());

    assertThat(response.header("X-Correlation-Id")).isEqualTo("corr-test-123");
  }

  @Test
  void generatesACorrelationIdWhenMissingOrUnsafe() {
    Response missing = get("/actuator/health", null);
    Response unsafe =
        send(
            HttpRequest.newBuilder(uri("/actuator/health"))
                .header("X-Correlation-Id", "bad id with spaces")
                .GET());

    assertThat(UUID.fromString(missing.header("X-Correlation-Id"))).isNotNull();
    assertThat(unsafe.header("X-Correlation-Id")).isNotEqualTo("bad id with spaces");
  }
}
