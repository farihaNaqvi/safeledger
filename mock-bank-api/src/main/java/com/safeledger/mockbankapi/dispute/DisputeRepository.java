package com.safeledger.mockbankapi.dispute;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

  Optional<Dispute> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

  boolean existsByTransactionIdAndStatus(UUID transactionId, Dispute.Status status);

  List<Dispute> findByCustomerIdOrderByCreatedAtDescIdAsc(UUID customerId);
}
