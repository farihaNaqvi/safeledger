package com.safeledger.bankmcpserver.config;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("safeledger")
public record SafeLedgerProperties(
    Bank bank,
    Identity identity,
    Actions actions,
    Limits limits,
    Provenance provenance,
    String timezone) {

  private static final int MIN_SECRET_BYTES = 32;

  public SafeLedgerProperties {
    byte[] secret =
        identity == null || identity.hmacSecret() == null
            ? new byte[0]
            : identity.hmacSecret().getBytes(StandardCharsets.UTF_8);
    if (secret.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "safeledger.identity.hmac-secret (IDENTITY_HMAC_SECRET) must be at least "
              + MIN_SECRET_BYTES
              + " bytes");
    }
    provenance = provenance == null ? new Provenance(true) : provenance;
  }

  public ZoneId zone() {
    return ZoneId.of(timezone);
  }

  public record Bank(String baseUrl) {}

  public record Identity(String hmacSecret, Duration maxAge) {
    public byte[] secretBytes() {
      return hmacSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
      return "Identity[hmacSecret=<redacted>, maxAge=" + maxAge + "]";
    }
  }

  public record Actions(Duration ttl, Duration expirySweepInterval) {}

  /**
   * @param enabled transfers, disputes and unfreezes must be grounded in what the customer typed
   */
  public record Provenance(boolean enabled) {}

  public record Limits(
      BigDecimal maxTransferPerAction,
      BigDecimal maxTransferPerDay,
      int maxWriteRequestsPerSession,
      int maxPendingActions,
      int maxToolCallsPerMinute) {}
}
