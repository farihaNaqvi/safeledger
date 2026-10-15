package com.safeledger.mockbankapi.payee;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayeeRepository extends JpaRepository<Payee, UUID> {

  List<Payee> findByCustomerIdOrderByAddedAtAscIdAsc(UUID customerId);

  Optional<Payee> findByIdAndCustomerId(UUID id, UUID customerId);
}
