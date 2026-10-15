package com.safeledger.bankmcpserver.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityTokenTest {

  private static final byte[] SECRET =
      "unit-test-secret-0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
  private static final Duration MAX_AGE = Duration.ofMinutes(5);
  private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
  private static final CallerIdentity CALLER =
      new CallerIdentity(UUID.fromString("11111111-1111-4111-8111-000000000001"), "session-1");

  @Test
  void roundTrips() {
    String token = IdentityToken.sign(CALLER, NOW, SECRET);

    assertThat(IdentityToken.verify(token, SECRET, NOW.plusSeconds(10), MAX_AGE)).isEqualTo(CALLER);
  }

  @Test
  void rejectsMissingAndMalformedTokens() {
    assertReason(null, "missing");
    assertReason("", "missing");
    assertReason("no-dot", "malformed");
    assertReason("a.b.c", "malformed");
    assertReason("!!!.???", "malformed");
  }

  @Test
  void rejectsAWrongSecret() {
    String token =
        IdentityToken.sign(
            CALLER,
            NOW,
            "another-secret-0123456789abcdefghijklmnop".getBytes(StandardCharsets.UTF_8));

    assertReason(token, "bad_signature");
  }

  @Test
  void rejectsATamperedCustomerId() {
    String token = IdentityToken.sign(CALLER, NOW, SECRET);
    String[] parts = token.split("\\.");
    String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
    String swapped =
        payload.replace(CALLER.customerId().toString(), "11111111-1111-4111-8111-000000000002");
    String forged =
        Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(swapped.getBytes(StandardCharsets.UTF_8))
            + "."
            + parts[1];

    assertReason(forged, "bad_signature");
  }

  @Test
  void rejectsExpiredAndFutureTokens() {
    String old = IdentityToken.sign(CALLER, NOW.minus(MAX_AGE).minusSeconds(1), SECRET);
    String future = IdentityToken.sign(CALLER, NOW.plusSeconds(120), SECRET);

    assertReason(old, "expired");
    assertReason(future, "issued_in_future");
  }

  @Test
  void refusesToSignAnUnsafeSessionId() {
    assertThatThrownBy(
            () ->
                IdentityToken.sign(
                    new CallerIdentity(CALLER.customerId(), "bad|session"), NOW, SECRET))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertReason(String token, String reason) {
    assertThatThrownBy(() -> IdentityToken.verify(token, SECRET, NOW, MAX_AGE))
        .isInstanceOf(IdentityToken.InvalidIdentityException.class)
        .hasMessage(reason);
  }
}
