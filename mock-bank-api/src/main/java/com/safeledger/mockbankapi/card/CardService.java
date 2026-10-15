package com.safeledger.mockbankapi.card;

import com.safeledger.mockbankapi.common.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freeze and unfreeze are naturally idempotent: freezing a frozen card (or unfreezing an active
 * one) succeeds and changes nothing. A blocked card can never be changed through this API.
 */
@Service
public class CardService {

  private final CardRepository cards;

  public CardService(CardRepository cards) {
    this.cards = cards;
  }

  @Transactional
  public Card freeze(UUID customerId, UUID cardId) {
    return changeStatus(customerId, cardId, Card.Status.FROZEN);
  }

  @Transactional
  public Card unfreeze(UUID customerId, UUID cardId) {
    return changeStatus(customerId, cardId, Card.Status.ACTIVE);
  }

  private Card changeStatus(UUID customerId, UUID cardId, Card.Status target) {
    Card card =
        cards
            .findByIdAndCustomerId(cardId, customerId)
            .orElseThrow(() -> ApiException.notFound("Card"));
    if (card.getStatus() == Card.Status.BLOCKED) {
      throw ApiException.conflict(
          "CARD_BLOCKED", "This card is blocked and cannot be frozen or unfrozen");
    }
    card.setStatus(target);
    return card;
  }
}
