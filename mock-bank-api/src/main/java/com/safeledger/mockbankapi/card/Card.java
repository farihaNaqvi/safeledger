package com.safeledger.mockbankapi.card;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** A payment card. Only the last four digits are stored; there is no full card number anywhere. */
@Entity
@Table(name = "card")
public class Card {

  public enum Status {
    ACTIVE,
    FROZEN,
    BLOCKED
  }

  @Id private UUID id;

  @Column(name = "customer_id", nullable = false)
  private UUID customerId;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(nullable = false, length = 4)
  private String last4;

  @Column(nullable = false, length = 20)
  private String network;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Status status;

  @Column(name = "monthly_limit", nullable = false, precision = 19, scale = 2)
  private BigDecimal monthlyLimit;

  protected Card() {}

  public UUID getId() {
    return id;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public String getLast4() {
    return last4;
  }

  public String getNetwork() {
    return network;
  }

  public Status getStatus() {
    return status;
  }

  public BigDecimal getMonthlyLimit() {
    return monthlyLimit;
  }

  void setStatus(Status status) {
    this.status = status;
  }
}
