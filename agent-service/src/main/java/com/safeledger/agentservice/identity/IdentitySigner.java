package com.safeledger.agentservice.identity;

import com.safeledger.agentservice.config.AgentProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Signs the {@code X-SafeLedger-Identity} header that bank-mcp-server verifies. Same format as the
 * server's IdentityToken: {@code base64url("v1|customer|session|issuedAt") "."
 * base64url(HMAC-SHA256)}. A fresh token is signed for every request, so the server's 5 minute
 * limit never cuts a long session short.
 */
@Component
public class IdentitySigner {

  public static final String HEADER = "X-SafeLedger-Identity";

  private static final String ALGORITHM = "HmacSHA256";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private final byte[] secret;

  public IdentitySigner(AgentProperties properties) {
    this.secret = properties.identity().secretBytes();
  }

  public String sign(UUID customerId, String sessionId) {
    String payload =
        String.join(
            "|",
            "v1",
            customerId.toString(),
            sessionId,
            Long.toString(Instant.now().getEpochSecond()));
    byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret, ALGORITHM));
      return ENCODER.encodeToString(payloadBytes)
          + "."
          + ENCODER.encodeToString(mac.doFinal(payloadBytes));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }
}
