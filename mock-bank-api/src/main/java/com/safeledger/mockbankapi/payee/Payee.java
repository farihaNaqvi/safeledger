package com.safeledger.mockbankapi.payee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A saved payee. Only a masked account number is stored. {@code name} is customer entered free text
 * and may contain prompt injection attempts.
 */
@Entity
@Table(name = "payee")
public class Payee {

  @Id private UUID id;

  @Column(name = "customer_id", nullable = false)
  private UUID customerId;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(name = "masked_account", nullable = false, length = 20)
  private String maskedAccount;

  @Column(nullable = false, length = 11)
  private String ifsc;

  @Column(name = "added_at", nullable = false)
  private Instant addedAt;

  protected Payee() {}

  public UUID getId() {
    return id;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public String getName() {
    return name;
  }

  public String getMaskedAccount() {
    return maskedAccount;
  }

  public String getIfsc() {
    return ifsc;
  }

  public Instant getAddedAt() {
    return addedAt;
  }
}
