package com.safeledger.mockbankapi.card;

import com.safeledger.mockbankapi.common.Headers;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CardController {

  public record CardResponse(
      UUID id,
      UUID accountId,
      String last4,
      String network,
      Card.Status status,
      BigDecimal monthlyLimit) {
    static CardResponse from(Card c) {
      return new CardResponse(
          c.getId(),
          c.getAccountId(),
          c.getLast4(),
          c.getNetwork(),
          c.getStatus(),
          c.getMonthlyLimit());
    }
  }

  private final CardRepository cards;
  private final CardService cardService;

  public CardController(CardRepository cards, CardService cardService) {
    this.cards = cards;
    this.cardService = cardService;
  }

  @GetMapping("/customers/{customerId}/cards")
  public List<CardResponse> list(
      @PathVariable UUID customerId, @RequestHeader(Headers.CUSTOMER_ID) UUID caller) {
    Headers.requireSameCustomer(customerId, caller);
    return cards.findByCustomerIdOrderByIdAsc(caller).stream().map(CardResponse::from).toList();
  }

  @PostMapping("/cards/{cardId}/freeze")
  public CardResponse freeze(
      @PathVariable UUID cardId, @RequestHeader(Headers.CUSTOMER_ID) UUID caller) {
    return CardResponse.from(cardService.freeze(caller, cardId));
  }

  @PostMapping("/cards/{cardId}/unfreeze")
  public CardResponse unfreeze(
      @PathVariable UUID cardId, @RequestHeader(Headers.CUSTOMER_ID) UUID caller) {
    return CardResponse.from(cardService.unfreeze(caller, cardId));
  }
}
