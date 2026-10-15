package com.safeledger.mockbankapi.transaction;

import com.safeledger.mockbankapi.account.AccountRepository;
import com.safeledger.mockbankapi.common.ApiException;
import com.safeledger.mockbankapi.common.Headers;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransactionController {

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;

  public record TransactionResponse(
      UUID id,
      UUID accountId,
      UUID cardId,
      BigDecimal amount,
      BankTransaction.Direction direction,
      String merchant,
      String description,
      String category,
      Instant postedAt) {
    static TransactionResponse from(BankTransaction t) {
      return new TransactionResponse(
          t.getId(),
          t.getAccountId(),
          t.getCardId(),
          t.getAmount(),
          t.getDirection(),
          t.getMerchant(),
          t.getDescription(),
          t.getCategory(),
          t.getPostedAt());
    }
  }

  private final AccountRepository accounts;
  private final BankTransactionRepository transactions;
  private final ZoneId bankZone;

  public TransactionController(
      AccountRepository accounts,
      BankTransactionRepository transactions,
      @Value("${bank.timezone}") String bankTimezone) {
    this.accounts = accounts;
    this.transactions = transactions;
    this.bankZone = ZoneId.of(bankTimezone);
  }

  /**
   * Newest first. {@code from} and {@code to} are inclusive calendar dates in the bank's time zone;
   * {@code merchant} is a case-insensitive substring match.
   */
  @GetMapping("/accounts/{accountId}/transactions")
  public List<TransactionResponse> search(
      @PathVariable UUID accountId,
      @RequestHeader(Headers.CUSTOMER_ID) UUID caller,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String merchant,
      @RequestParam(required = false) BigDecimal minAmount,
      @RequestParam(required = false) BigDecimal maxAmount,
      @RequestParam(required = false) Integer limit) {
    accounts
        .findByIdAndCustomerId(accountId, caller)
        .orElseThrow(() -> ApiException.notFound("Account"));

    int pageSize = limit == null ? DEFAULT_LIMIT : limit;
    if (pageSize < 1 || pageSize > MAX_LIMIT) {
      throw ApiException.badRequest(
          "INVALID_PARAMETER", "limit must be between 1 and " + MAX_LIMIT);
    }
    if (from != null && to != null && from.isAfter(to)) {
      throw ApiException.badRequest("INVALID_PARAMETER", "from must not be after to");
    }
    if (minAmount != null && maxAmount != null && minAmount.compareTo(maxAmount) > 0) {
      throw ApiException.badRequest(
          "INVALID_PARAMETER", "minAmount must not be greater than maxAmount");
    }

    Specification<BankTransaction> spec =
        (root, query, cb) -> {
          List<Predicate> where = new ArrayList<>();
          where.add(cb.equal(root.get("accountId"), accountId));
          if (from != null) {
            where.add(
                cb.greaterThanOrEqualTo(
                    root.get("postedAt"), from.atStartOfDay(bankZone).toInstant()));
          }
          if (to != null) {
            where.add(
                cb.lessThan(
                    root.get("postedAt"), to.plusDays(1).atStartOfDay(bankZone).toInstant()));
          }
          if (merchant != null && !merchant.isBlank()) {
            where.add(
                cb.like(
                    cb.lower(root.get("merchant")),
                    "%" + escapeLike(merchant.strip().toLowerCase(Locale.ROOT)) + "%",
                    '\\'));
          }
          if (minAmount != null) {
            where.add(cb.greaterThanOrEqualTo(root.get("amount"), minAmount));
          }
          if (maxAmount != null) {
            where.add(cb.lessThanOrEqualTo(root.get("amount"), maxAmount));
          }
          return cb.and(where.toArray(Predicate[]::new));
        };

    Sort newestFirst = Sort.by(Sort.Order.desc("postedAt"), Sort.Order.asc("id"));
    return transactions.findAll(spec, PageRequest.of(0, pageSize, newestFirst)).stream()
        .map(TransactionResponse::from)
        .toList();
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
