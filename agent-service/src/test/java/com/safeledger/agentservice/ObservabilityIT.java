package com.safeledger.agentservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;

/** Trace and correlation propagation to the MCP server, and the Prometheus endpoint. */
@AutoConfigureMetrics
@AutoConfigureTracing
class ObservabilityIT extends AgentIntegrationTestBase {

  @Test
  void aTurnAndItsConfirmShareOneTraceDownToTheMcpServer() {
    Browser browser = loggedIn(RIYA);
    model.thenCall("request_card_freeze", "{\"cardLast4\":\"4821\"}").thenReply("Please confirm.");

    Response chat = browser.chat("Freeze my card ending 4821");
    String traceId = chat.raw().headers().firstValue("X-Trace-Id").orElseThrow();
    String correlationId = chat.raw().headers().firstValue("X-Correlation-Id").orElseThrow();
    String actionId = chat.json("$.pendingActions[0].id");

    assertThat(traceId).matches("[0-9a-f]{32}");
    // Every MCP request made for this turn, including the tool call, carries both IDs.
    assertThat(mcp.requests)
        .filteredOn(r -> r.path().equals("/mcp") && correlationId.equals(r.correlationId()))
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.traceparent()).contains(traceId));

    // The page sends Confirm in the same trace, so the whole flow is one trace.
    Response confirm =
        browser.send(
            browser
                .request("/api/actions/" + actionId + "/confirm")
                .header("X-Requested-With", "SafeLedger")
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .POST(HttpRequest.BodyPublishers.noBody()));

    assertThat(confirm.status()).isEqualTo(200);
    assertThat(mcp.requests)
        .filteredOn(r -> r.path().endsWith("/" + actionId + "/confirm"))
        .singleElement()
        .satisfies(r -> assertThat(r.traceparent()).contains(traceId));
  }

  @Test
  void prometheusShowsTokensCostSessionsAndGuardrailButNoCustomerIds() {
    Browser browser = loggedIn(RIYA);
    model.thenReply("Hello");
    browser.chat("Hi");
    browser.chat("please " + BLOCKED_WORD);

    Response r = new Browser().get("/actuator/prometheus");

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body())
        .contains("safeledger_model_tokens_total{model=\"scripted-model\",type=\"input\"}")
        .contains("safeledger_model_tokens_total{model=\"scripted-model\",type=\"output\"}")
        .contains("safeledger_model_cost_usd_total{model=\"scripted-model\"}")
        .contains("safeledger_chat_sessions_total")
        .contains("safeledger_guardrail_interventions_total{kind=\"blocked\",source=\"input\"}")
        .contains("safeledger_chat_turn_seconds_count")
        .doesNotContain(RIYA.toString());
  }
}
