package com.safeledger.mockbankapi.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Validation and request fingerprinting for {@code Idempotency-Key} handling. */
public final class Idempotency {

  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_.:-]{1,100}$");
  private static final char SEPARATOR = '\u001f';

  private Idempotency() {}

  public static void validateKey(String key) {
    if (key == null || !KEY.matcher(key).matches()) {
      throw ApiException.badRequest(
          "INVALID_IDEMPOTENCY_KEY",
          "Idempotency-Key must be 1-100 letters, digits or any of - _ . :");
    }
  }

  /**
   * Fingerprint of the request body. Reusing a key with a different body is rejected rather than
   * silently replaying the first result.
   */
  public static String fingerprint(Object... parts) {
    StringBuilder canonical = new StringBuilder();
    for (Object part : parts) {
      canonical.append(part == null ? "" : part.toString()).append(SEPARATOR);
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  public static ApiException keyReused() {
    return ApiException.unprocessable(
        "IDEMPOTENCY_KEY_REUSED",
        "This Idempotency-Key was already used with a different request body");
  }
}
