package com.safeledger.mockbankapi.dispute;

import com.safeledger.mockbankapi.common.ApiException;
import com.safeledger.mockbankapi.common.Idempotency;
import com.safeledger.mockbankapi.transaction.BankTransaction;
import com.safeledger.mockbankapi.transaction.BankTransactionRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Raises a dispute at most once per (customer, idempotency key), and allows at most one open
 * dispute per transaction. Both rules are backed by unique constraints, so concurrent requests
 * cannot get around them.
 */
@Service
public class DisputeService {

  private static final Logger log = LoggerFactory.getLogger(DisputeService.class);

  public record Result(Dispute dispute, boolean replayed) {}

  private final DisputeRepository disputes;
  private final BankTransactionRepository transactions;
  private final TransactionTemplate tx;

  public DisputeService(
      DisputeRepository disputes,
      BankTransactionRepository transactions,
      PlatformTransactionManager transactionManager) {
    this.disputes = disputes;
    this.transactions = transactions;
    this.tx = new TransactionTemplate(transactionManager);
  }

  public Result create(UUID customerId, String idempotencyKey, UUID transactionId, String reason) {
    Idempotency.validateKey(idempotencyKey);
    String hash = Idempotency.fingerprint(transactionId, reason);
    try {
      return tx.execute(status -> execute(customerId, idempotencyKey, hash, transactionId, reason));
    } catch (DataIntegrityViolationException e) {
      return tx.execute(
          status -> {
            var existing = disputes.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
            if (existing.isPresent()) {
              return replay(existing.get(), hash);
            }
            if (disputes.existsByTransactionIdAndStatus(transactionId, Dispute.Status.OPEN)) {
              throw alreadyOpen();
            }
            throw e;
          });
    }
  }

  private Result execute(
      UUID customerId, String idempotencyKey, String hash, UUID transactionId, String reason) {
    var existing = disputes.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
    if (existing.isPresent()) {
      return replay(existing.get(), hash);
    }
    BankTransaction transaction =
        transactions
            .findOwned(transactionId, customerId)
            .orElseThrow(() -> ApiException.notFound("Transaction"));
    if (disputes.existsByTransactionIdAndStatus(transaction.getId(), Dispute.Status.OPEN)) {
      throw alreadyOpen();
    }
    Dispute dispute =
        disputes.saveAndFlush(
            Dispute.open(
                customerId, transaction.getId(), reason, idempotencyKey, hash, Instant.now()));
    log.info("Dispute opened: disputeId={}", dispute.getId());
    return new Result(dispute, false);
  }

  private static Result replay(Dispute existing, String hash) {
    if (!existing.getRequestHash().equals(hash)) {
      throw Idempotency.keyReused();
    }
    return new Result(existing, true);
  }

  private static ApiException alreadyOpen() {
    return ApiException.conflict(
        "DISPUTE_ALREADY_OPEN", "There is already an open dispute for this transaction");
  }
}
