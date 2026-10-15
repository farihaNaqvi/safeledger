package com.safeledger.mockbankapi.transfer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

  Optional<Transfer> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);
}
