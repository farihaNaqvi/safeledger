package com.safeledger.bankmcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Tool spans join the caller's trace and reach the bank; metrics on /actuator/prometheus. */
@AutoConfigureMetrics
@AutoConfigureTracing
@Import(ObservabilityIT.BankHeaders.class)
class ObservabilityIT extends McpIntegrationTestBase {

  /** traceparent headers the (fake) bank API received. */
  static final List<String> BANK_TRACEPARENTS = new CopyOnWriteArrayList<>();

  @TestConfiguration(proxyBeanMethods = false)
  static class BankHeaders {
    @Bean
    FilterRegistrationBean<Filter> recordBankTraceparent() {
      FilterRegistrationBean<Filter> bean =
          new FilterRegistrationBean<>(
              (request, response, chain) -> {
                String traceparent = ((HttpServletRequest) request).getHeader("traceparent");
                if (traceparent != null) {
                  BANK_TRACEPARENTS.add(traceparent);
                }
                chain.doFilter(request, response);
              });
      bean.addUrlPatterns("/fake-bank/*");
      return bean;
    }
  }

  @Test
  void aToolCallContinuesTheCallersTraceAndPassesItToTheBank() {
    UUID me = newCustomer();
    bank.customer(me);
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    McpSyncClient client =
        connect(me, newSession(), Map.of("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01"));

    assertThat(call(client, "list_cards", Map.of()).isError()).isFalse();

    assertThat(BANK_TRACEPARENTS).anySatisfy(t -> assertThat(t).contains(traceId));
  }

  @Test
  void prometheusShowsToolOutcomesActionsAndInjectionFlagsButNoCustomerIds() throws Exception {
    UUID me = newCustomer();
    String session = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    McpSyncClient client = connect(me, session);
    Map<String, Object> transfer =
        Map.of(
            "fromAccountId",
            data.savings.toString(),
            "payeeId",
            data.injectionPayee.toString(),
            "amount",
            "100.00");

    ToolResult created = call(client, "request_transfer", transfer);
    assertThat(confirm(me, session, created.json("$.actionId")).status()).isEqualTo(200);
    ToolResult tooBig =
        call(
            client,
            "request_transfer",
            Map.of(
                "fromAccountId", data.savings.toString(),
                "payeeId", data.payee.toString(),
                "amount", "50000.01"));

    assertThat(created.<List<String>>json("$.flags")).isNotEmpty();
    assertThat(tooBig.<String>json("$.error")).isEqualTo("TRANSFER_LIMIT");
    Integer audited =
        jdbc.queryForObject(
            "select count(*) from audit_event where customer_id = ? and event_type ="
                + " 'INJECTION_FLAGGED' and tool_name = 'request_transfer'",
            Integer.class,
            me);
    assertThat(audited).isPositive();

    HttpResponse<String> metrics =
        HTTP.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(metrics.statusCode()).isEqualTo(200);
    assertThat(metrics.body())
        .contains(
            "safeledger_tool_seconds_count{error=\"none\",outcome=\"OK\",tool=\"request_transfer\"}")
        .contains(
            "safeledger_tool_seconds_count{error=\"none\",outcome=\"TRANSFER_LIMIT\",tool=\"request_transfer\"}")
        .contains("safeledger_actions_total{event=\"created\",tool=\"request_transfer\"}")
        .contains("safeledger_actions_total{event=\"executed\",tool=\"request_transfer\"}")
        .containsPattern(
            "safeledger_injection_flags_total\\{rule=\"[a-z_]+\",tool=\"request_transfer\"}")
        .doesNotContain(me.toString())
        .doesNotContain(session);
  }
}
