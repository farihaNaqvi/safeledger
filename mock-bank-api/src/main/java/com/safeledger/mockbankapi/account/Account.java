package com.safeledger.mockbankapi.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "account")
public class Account {

  public enum Type {
    SAVINGS,
    CURRENT
  }

  @Id private UUID id;

  @Column(name = "customer_id", nullable = false)
  private UUID customerId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Type type;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal balance;

  protected Account() {}

  public UUID getId() {
    return id;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public Type getType() {
    return type;
  }

  public String getCurrency() {
    return currency;
  }

  public BigDecimal getBalance() {
    return balance;
  }

  public boolean canCover(BigDecimal amount) {
    return balance.compareTo(amount) >= 0;
  }

  public void debit(BigDecimal amount) {
    if (!canCover(amount)) {
      throw new IllegalStateException("Insufficient balance");
    }
    balance = balance.subtract(amount);
  }
}
