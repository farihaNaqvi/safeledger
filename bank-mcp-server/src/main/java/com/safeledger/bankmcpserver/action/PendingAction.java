package com.safeledger.bankmcpserver.action;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PendingAction(
    UUID id,
    UUID customerId,
    String sessionId,
    String toolName,
    Map<String, Object> arguments,
    String summary,
    Status status,
    String idempotencyKey,
    BigDecimal amount,
    Instant createdAt,
    Instant expiresAt,
    Instant executedAt,
    String result) {

  public enum Status {
    PENDING,
    CONFIRMED,
    EXECUTED,
    FAILED,
    EXPIRED,
    CANCELLED
  }

  public String argument(String name) {
    Object value = arguments.get(name);
    return value == null ? null : value.toString();
  }
}
