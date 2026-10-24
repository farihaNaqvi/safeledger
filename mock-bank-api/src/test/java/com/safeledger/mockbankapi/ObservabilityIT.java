package com.safeledger.mockbankapi;

import static com.safeledger.mockbankapi.SeedData.ACCOUNT_1;
import static com.safeledger.mockbankapi.SeedData.CUSTOMER_1;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;

/** /actuator/prometheus, and request metrics keyed by URI template, never by IDs. */
@AutoConfigureMetrics
@AutoConfigureTracing
class ObservabilityIT extends IntegrationTestBase {

  @Test
  void prometheusShowsRequestsByTemplateWithoutIds() {
    assertThat(get("/accounts/" + ACCOUNT_1 + "/balance", CUSTOMER_1).status()).isEqualTo(200);

    Response metrics = send(HttpRequest.newBuilder(uri("/actuator/prometheus")).GET());

    assertThat(metrics.status()).isEqualTo(200);
    assertThat(metrics.body())
        .contains("uri=\"/accounts/{accountId}/balance\"")
        .doesNotContain(ACCOUNT_1.toString())
        .doesNotContain(CUSTOMER_1.toString());
  }

  @Test
  void anIncomingTraceIsContinuedAndTheCorrelationIdKept() {
    Response r =
        send(
            HttpRequest.newBuilder(uri("/customers/" + CUSTOMER_1 + "/accounts"))
                .header("X-Customer-Id", CUSTOMER_1.toString())
                .header("X-Correlation-Id", "corr-123")
                .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .GET());

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.header("X-Correlation-Id")).isEqualTo("corr-123");
  }
}
