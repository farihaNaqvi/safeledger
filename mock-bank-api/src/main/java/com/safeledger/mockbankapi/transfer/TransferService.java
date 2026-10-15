package com.safeledger.mockbankapi.transfer;

import com.safeledger.mockbankapi.account.Account;
import com.safeledger.mockbankapi.account.AccountRepository;
import com.safeledger.mockbankapi.common.ApiException;
import com.safeledger.mockbankapi.common.Idempotency;
import com.safeledger.mockbankapi.payee.Payee;
import com.safeledger.mockbankapi.payee.PayeeRepository;
import com.safeledger.mockbankapi.transaction.BankTransaction;
import com.safeledger.mockbankapi.transaction.BankTransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes a transfer at most once per (customer, idempotency key).
 *
 * <p>The sender's account row is locked first, so concurrent requests for the same account,
 * including retries with the same key, run one after another. The retry then finds the stored
 * transfer and replays it instead of debiting again.
 */
@Service
public class TransferService {

  private static final Logger log = LoggerFactory.getLogger(TransferService.class);

  public record Request(UUID fromAccountId, UUID payeeId, BigDecimal amount, String note) {}

  public record Result(Transfer transfer, boolean replayed) {}

  private final AccountRepository accounts;
  private final PayeeRepository payees;
  private final TransferRepository transfers;
  private final BankTransactionRepository transactions;
  private final TransactionTemplate tx;

  public TransferService(
      AccountRepository accounts,
      PayeeRepository payees,
      TransferRepository transfers,
      BankTransactionRepository transactions,
      PlatformTransactionManager transactionManager) {
    this.accounts = accounts;
    this.payees = payees;
    this.transfers = transfers;
    this.transactions = transactions;
    this.tx = new TransactionTemplate(transactionManager);
  }

  public Result create(UUID customerId, String idempotencyKey, Request request) {
    Idempotency.validateKey(idempotencyKey);
    BigDecimal amount = request.amount().setScale(2, RoundingMode.UNNECESSARY);
    String note = request.note() == null || request.note().isBlank() ? null : request.note();
    String hash = Idempotency.fingerprint(request.fromAccountId(), request.payeeId(), amount, note);
    try {
      return tx.execute(status -> execute(customerId, idempotencyKey, hash, request, amount, note));
    } catch (DataIntegrityViolationException e) {
      // Safety net: if two requests with the same key ever slip past the lock, the unique
      // constraint stops the second one and it replays the first.
      return tx.execute(
          status ->
              transfers
                  .findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                  .map(existing -> replay(existing, hash))
                  .orElseThrow(() -> e));
    }
  }

  private Result execute(
      UUID customerId,
      String idempotencyKey,
      String hash,
      Request request,
      BigDecimal amount,
      String note) {
    Account account =
        accounts
            .findOwnedForUpdate(request.fromAccountId(), customerId)
            .orElseThrow(() -> ApiException.notFound("Account"));

    var existing = transfers.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
    if (existing.isPresent()) {
      return replay(existing.get(), hash);
    }

    // Unknown payee and another customer's payee get the same answer.
    Payee payee =
        payees
            .findByIdAndCustomerId(request.payeeId(), customerId)
            .orElseThrow(
                () ->
                    ApiException.unprocessable(
                        "UNKNOWN_PAYEE", "Payee is not one of this customer's saved payees"));

    if (!account.canCover(amount)) {
      throw ApiException.unprocessable(
          "INSUFFICIENT_FUNDS", "Account balance is too low for this transfer");
    }

    Instant now = Instant.now();
    account.debit(amount);
    Transfer transfer =
        transfers.saveAndFlush(
            Transfer.completed(
                customerId,
                account.getId(),
                payee.getId(),
                amount,
                note,
                idempotencyKey,
                hash,
                now));
    transactions.save(
        BankTransaction.transferDebit(account.getId(), amount, payee.getName(), note, now));
    log.info("Transfer completed: transferId={}", transfer.getId());
    return new Result(transfer, false);
  }

  private static Result replay(Transfer existing, String hash) {
    if (!existing.getRequestHash().equals(hash)) {
      throw Idempotency.keyReused();
    }
    return new Result(existing, true);
  }
}
