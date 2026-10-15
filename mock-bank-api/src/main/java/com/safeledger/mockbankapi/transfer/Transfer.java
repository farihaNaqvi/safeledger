package com.safeledger.mockbankapi.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfer")
public class Transfer {

  /** Failed transfers are rejected without a record, so every stored transfer completed. */
  public enum Status {
    COMPLETED
  }

  @Id private UUID id;

  @Column(name = "customer_id", nullable = false)
  private UUID customerId;

  @Column(name = "from_account_id", nullable = false)
  private UUID fromAccountId;

  @Column(name = "payee_id", nullable = false)
  private UUID payeeId;

  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal amount;

  @Column(length = 200)
  private String note;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Status status;

  @Column(name = "idempotency_key", nullable = false, length = 100)
  private String idempotencyKey;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Transfer() {}

  static Transfer completed(
      UUID customerId,
      UUID fromAccountId,
      UUID payeeId,
      BigDecimal amount,
      String note,
      String idempotencyKey,
      String requestHash,
      Instant createdAt) {
    Transfer t = new Transfer();
    t.id = UUID.randomUUID();
    t.customerId = customerId;
    t.fromAccountId = fromAccountId;
    t.payeeId = payeeId;
    t.amount = amount;
    t.note = note;
    t.status = Status.COMPLETED;
    t.idempotencyKey = idempotencyKey;
    t.requestHash = requestHash;
    t.createdAt = createdAt;
    return t;
  }

  public UUID getId() {
    return id;
  }

  public UUID getFromAccountId() {
    return fromAccountId;
  }

  public UUID getPayeeId() {
    return payeeId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getNote() {
    return note;
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
