package com.safeledger.mockbankapi.account;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

  List<Account> findByCustomerIdOrderByIdAsc(UUID customerId);

  Optional<Account> findByIdAndCustomerId(UUID id, UUID customerId);

  /** Locks the row so concurrent transfers from one account are applied one at a time. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from Account a where a.id = :id and a.customerId = :customerId")
  Optional<Account> findOwnedForUpdate(@Param("id") UUID id, @Param("customerId") UUID customerId);
}
