package com.safeledger.mockbankapi.card;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardRepository extends JpaRepository<Card, UUID> {

  List<Card> findByCustomerIdOrderByIdAsc(UUID customerId);

  Optional<Card> findByIdAndCustomerId(UUID id, UUID customerId);
}
