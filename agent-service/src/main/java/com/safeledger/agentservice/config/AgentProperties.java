package com.safeledger.agentservice.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("safeledger")
public record AgentProperties(
    String mcpUrl,
    Identity identity,
    Agent agent,
    Guardrail guardrail,
    Cost cost,
    List<DemoCustomer> customers) {

  private static final int MIN_SECRET_BYTES = 32;

  public AgentProperties {
    if (identity == null
        || identity.hmacSecret() == null
        || identity.hmacSecret().getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "safeledger.identity.hmac-secret (IDENTITY_HMAC_SECRET) must be at least "
              + MIN_SECRET_BYTES
              + " bytes");
    }
    customers = customers == null ? List.of() : List.copyOf(customers);
    cost = cost == null ? new Cost(null, null) : cost;
  }

  public record Identity(String hmacSecret) {
    public byte[] secretBytes() {
      return hmacSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
      return "Identity[hmacSecret=<redacted>]";
    }
  }

  public record Agent(
      String systemPrompt,
      int maxToolCallsPerTurn,
      int maxModelRoundsPerTurn,
      Duration turnTimeout,
      int memoryMessages,
      String timezone) {
    public ZoneId zone() {
      return ZoneId.of(timezone);
    }
  }

  public record Guardrail(boolean enabled, String id, String version, String region) {}

  /** Model prices in USD per million tokens, for the estimated-cost metric. */
  public record Cost(BigDecimal inputUsdPerMillion, BigDecimal outputUsdPerMillion) {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    public Cost {
      inputUsdPerMillion = inputUsdPerMillion == null ? BigDecimal.ZERO : inputUsdPerMillion;
      outputUsdPerMillion = outputUsdPerMillion == null ? BigDecimal.ZERO : outputUsdPerMillion;
    }

    public BigDecimal usd(long inputTokens, long outputTokens) {
      return inputUsdPerMillion
          .multiply(BigDecimal.valueOf(inputTokens))
          .add(outputUsdPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
          .divide(MILLION, 8, RoundingMode.HALF_UP);
    }
  }

  public record DemoCustomer(UUID id, String name) {}
}
