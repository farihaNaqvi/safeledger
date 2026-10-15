package com.safeledger.mockbankapi.payee;

import com.safeledger.mockbankapi.common.Headers;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PayeeController {

  public record PayeeResponse(
      UUID id, String name, String maskedAccount, String ifsc, Instant addedAt) {
    static PayeeResponse from(Payee p) {
      return new PayeeResponse(
          p.getId(), p.getName(), p.getMaskedAccount(), p.getIfsc(), p.getAddedAt());
    }
  }

  private final PayeeRepository payees;

  public PayeeController(PayeeRepository payees) {
    this.payees = payees;
  }

  @GetMapping("/customers/{customerId}/payees")
  public List<PayeeResponse> list(
      @PathVariable UUID customerId, @RequestHeader(Headers.CUSTOMER_ID) UUID caller) {
    Headers.requireSameCustomer(customerId, caller);
    return payees.findByCustomerIdOrderByAddedAtAscIdAsc(caller).stream()
        .map(PayeeResponse::from)
        .toList();
  }
}
