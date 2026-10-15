package com.safeledger.bankmcpserver.identity;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed identity header sent by agent-service on every request to this server.
 *
 * <p>Format: {@code base64url(payload) "." base64url(HMAC-SHA256(secret, payload))} where payload
 * is {@code v1|<customerId>|<sessionId>|<issuedAtEpochSeconds>}. A token is accepted for {@code
 * maxAge} after it was issued, with a small allowance for clock skew.
 */
public final class IdentityToken {

  public static final String HEADER = "X-SafeLedger-Identity";

  private static final String VERSION = "v1";
  private static final String ALGORITHM = "HmacSHA256";
  private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
  private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private IdentityToken() {}

  public static String sign(CallerIdentity identity, Instant issuedAt, byte[] secret) {
    if (!SESSION_ID.matcher(identity.sessionId()).matches()) {
      throw new IllegalArgumentException("Invalid session id");
    }
    String payload =
        String.join(
            "|",
            VERSION,
            identity.customerId().toString(),
            identity.sessionId(),
            Long.toString(issuedAt.getEpochSecond()));
    byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
    return ENCODER.encodeToString(payloadBytes)
        + "."
        + ENCODER.encodeToString(hmac(secret, payloadBytes));
  }

  /**
   * Returns the identity if the token is well formed, correctly signed and fresh.
   *
   * @throws InvalidIdentityException with a reason safe to log (never the token itself)
   */
  public static CallerIdentity verify(String token, byte[] secret, Instant now, Duration maxAge) {
    if (token == null || token.isBlank()) {
      throw new InvalidIdentityException("missing");
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != 2) {
      throw new InvalidIdentityException("malformed");
    }
    byte[] payloadBytes;
    byte[] signature;
    try {
      payloadBytes = DECODER.decode(parts[0]);
      signature = DECODER.decode(parts[1]);
    } catch (IllegalArgumentException e) {
      throw new InvalidIdentityException("malformed");
    }
    if (!MessageDigest.isEqual(hmac(secret, payloadBytes), signature)) {
      throw new InvalidIdentityException("bad_signature");
    }

    String[] fields = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", -1);
    if (fields.length != 4 || !VERSION.equals(fields[0])) {
      throw new InvalidIdentityException("malformed");
    }
    UUID customerId;
    Instant issuedAt;
    try {
      customerId = UUID.fromString(fields[1]);
      issuedAt = Instant.ofEpochSecond(Long.parseLong(fields[3]));
    } catch (IllegalArgumentException e) {
      throw new InvalidIdentityException("malformed");
    }
    if (!SESSION_ID.matcher(fields[2]).matches()) {
      throw new InvalidIdentityException("malformed");
    }
    if (issuedAt.isAfter(now.plus(CLOCK_SKEW))) {
      throw new InvalidIdentityException("issued_in_future");
    }
    if (issuedAt.plus(maxAge).isBefore(now)) {
      throw new InvalidIdentityException("expired");
    }
    return new CallerIdentity(customerId, fields[2]);
  }

  private static byte[] hmac(byte[] secret, byte[] payload) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret, ALGORITHM));
      return mac.doFinal(payload);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  public static final class InvalidIdentityException extends RuntimeException {
    public InvalidIdentityException(String reason) {
      super(reason);
    }
  }
}
