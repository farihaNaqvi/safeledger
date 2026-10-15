package com.safeledger.mockbankapi.transaction;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BankTransactionRepository
    extends JpaRepository<BankTransaction, UUID>, JpaSpecificationExecutor<BankTransaction> {

  /** The transaction, only if it was posted to one of the customer's accounts. */
  @Query(
      "select t from BankTransaction t where t.id = :id and t.accountId in"
          + " (select a.id from Account a where a.customerId = :customerId)")
  Optional<BankTransaction> findOwned(@Param("id") UUID id, @Param("customerId") UUID customerId);

  /** As {@link #findOwned}, locking the row so disputes on one transaction run one at a time. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select t from BankTransaction t where t.id = :id and t.accountId in"
          + " (select a.id from Account a where a.customerId = :customerId)")
  Optional<BankTransaction> findOwnedForUpdate(
      @Param("id") UUID id, @Param("customerId") UUID customerId);
}
