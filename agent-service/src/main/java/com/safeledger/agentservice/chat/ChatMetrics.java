package com.safeledger.agentservice.chat;

import com.safeledger.agentservice.config.AgentProperties;
import com.safeledger.agentservice.guardrail.Guardrail;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Tokens, estimated cost and guardrail interventions. Cost per session on the dashboard is
 * safeledger.model.cost divided by safeledger.chat.sessions (sessions that sent at least one
 * message). Tags never carry customer or session IDs.
 */
@Component
public class ChatMetrics {

  private final MeterRegistry meters;
  private final AgentProperties.Cost cost;

  public ChatMetrics(MeterRegistry meters, AgentProperties properties) {
    this.meters = meters;
    this.cost = properties.cost();
  }

  void turn(String model, AgentService.TokenUsage usage, boolean firstTurnOfSession) {
    String m = model == null ? "unknown" : model;
    meters
        .counter("safeledger.model.tokens", "type", "input", "model", m)
        .increment(usage.inputTokens());
    meters
        .counter("safeledger.model.tokens", "type", "output", "model", m)
        .increment(usage.outputTokens());
    Counter.builder("safeledger.model.cost")
        .baseUnit("usd")
        .description("Estimated model cost from configured prices")
        .tag("model", m)
        .register(meters)
        .increment(cost.usd(usage.inputTokens(), usage.outputTokens()).doubleValue());
    if (firstTurnOfSession) {
      meters.counter("safeledger.chat.sessions").increment();
    }
  }

  void guardrail(Guardrail.Source source, Guardrail.Result result) {
    if (result.intervened()) {
      meters
          .counter(
              "safeledger.guardrail.interventions",
              "source",
              source.name().toLowerCase(Locale.ROOT),
              "kind",
              result.blocked() ? "blocked" : "masked")
          .increment();
    }
  }
}
