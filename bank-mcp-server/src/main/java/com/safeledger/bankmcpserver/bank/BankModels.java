package com.safeledger.bankmcpserver.bank;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Response shapes of the mock bank API (see api/bank-api.yaml). */
public final class BankModels {

  private BankModels() {}

  public record Account(UUID id, String type, String currency, BigDecimal balance) {}

  public record Balance(UUID accountId, String currency, BigDecimal balance, Instant asOf) {}

  public record Card(
      UUID id,
      UUID accountId,
      String last4,
      String network,
      String status,
      BigDecimal monthlyLimit) {}

  public record Transaction(
      UUID id,
      UUID accountId,
      UUID cardId,
      BigDecimal amount,
      String direction,
      String merchant,
      String description,
      String category,
      Instant postedAt) {}

  public record Payee(UUID id, String name, String maskedAccount, String ifsc, Instant addedAt) {}

  public record Dispute(
      UUID id, UUID transactionId, String reason, String status, Instant createdAt) {}

  public record Transfer(
      UUID id,
      UUID fromAccountId,
      UUID payeeId,
      BigDecimal amount,
      String currency,
      String note,
      String status,
      Instant createdAt) {}

  public record TransactionQuery(
      String from,
      String to,
      String merchant,
      BigDecimal minAmount,
      BigDecimal maxAmount,
      int limit) {}
}
