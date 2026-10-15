package com.safeledger.mockbankapi.dispute;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dispute")
public class Dispute {

  public enum Status {
    OPEN,
    RESOLVED,
    REJECTED
  }

  @Id private UUID id;

  @Column(name = "customer_id", nullable = false)
  private UUID customerId;

  @Column(name = "transaction_id", nullable = false)
  private UUID transactionId;

  @Column(nullable = false, length = 500)
  private String reason;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Status status;

  @Column(name = "idempotency_key", nullable = false, length = 100)
  private String idempotencyKey;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Dispute() {}

  static Dispute open(
      UUID customerId,
      UUID transactionId,
      String reason,
      String idempotencyKey,
      String requestHash,
      Instant createdAt) {
    Dispute d = new Dispute();
    d.id = UUID.randomUUID();
    d.customerId = customerId;
    d.transactionId = transactionId;
    d.reason = reason;
    d.status = Status.OPEN;
    d.idempotencyKey = idempotencyKey;
    d.requestHash = requestHash;
    d.createdAt = createdAt;
    return d;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTransactionId() {
    return transactionId;
  }

  public String getReason() {
    return reason;
  }

  public Status getStatus() {
    return status;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
