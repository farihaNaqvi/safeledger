package com.safeledger.mockbankapi.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A posted transaction. {@code merchant} and {@code description} are free text from outside the
 * bank's control and may contain prompt injection attempts; consumers must treat them as data.
 */
@Entity
@Table(name = "bank_transaction")
public class BankTransaction {

  public enum Direction {
    DEBIT,
    CREDIT
  }

  private static final int MAX_DESCRIPTION = 500;

  @Id private UUID id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "card_id")
  private UUID cardId;

  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private Direction direction;

  @Column(nullable = false, length = 200)
  private String merchant;

  @Column(nullable = false, length = MAX_DESCRIPTION)
  private String description;

  @Column(nullable = false, length = 50)
  private String category;

  @Column(name = "posted_at", nullable = false)
  private Instant postedAt;

  protected BankTransaction() {}

  /** The debit written to the sender's account when a transfer completes. */
  public static BankTransaction transferDebit(
      UUID accountId, BigDecimal amount, String payeeName, String note, Instant postedAt) {
    BankTransaction t = new BankTransaction();
    t.id = UUID.randomUUID();
    t.accountId = accountId;
    t.amount = amount;
    t.direction = Direction.DEBIT;
    t.merchant = payeeName;
    String description =
        note == null || note.isBlank()
            ? "Transfer to " + payeeName
            : "Transfer to " + payeeName + ": " + note;
    t.description =
        description.length() > MAX_DESCRIPTION
            ? description.substring(0, MAX_DESCRIPTION)
            : description;
    t.category = "TRANSFER";
    t.postedAt = postedAt;
    return t;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public UUID getCardId() {
    return cardId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public Direction getDirection() {
    return direction;
  }

  public String getMerchant() {
    return merchant;
  }

  public String getDescription() {
    return description;
  }

  public String getCategory() {
    return category;
  }

  public Instant getPostedAt() {
    return postedAt;
  }
}
