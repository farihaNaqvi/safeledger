package com.safeledger.mockbankapi.account;

import com.safeledger.mockbankapi.common.ApiException;
import com.safeledger.mockbankapi.common.Headers;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

  public record AccountResponse(UUID id, Account.Type type, String currency, BigDecimal balance) {
    static AccountResponse from(Account a) {
      return new AccountResponse(a.getId(), a.getType(), a.getCurrency(), a.getBalance());
    }
  }

  public record BalanceResponse(
      UUID accountId, String currency, BigDecimal balance, Instant asOf) {}

  private final AccountRepository accounts;

  public AccountController(AccountRepository accounts) {
    this.accounts = accounts;
  }

  @GetMapping("/customers/{customerId}/accounts")
  public List<AccountResponse> list(
      @PathVariable UUID customerId, @RequestHeader(Headers.CUSTOMER_ID) UUID caller) {
    Headers.requireSameCustomer(customerId, caller);
    return accounts.findByCustomerIdOrderByIdAsc(caller).stream()
        .map(AccountResponse::from)
        .toList();
  }

  @GetMapping("/accounts/{accountId}/balance")
  public BalanceResponse balance(
      @PathVariable UUID accountId, @RequestHeader(Headers.CUSTOMER_ID) UUID caller) {
    Account account =
        accounts
            .findByIdAndCustomerId(accountId, caller)
            .orElseThrow(() -> ApiException.notFound("Account"));
    return new BalanceResponse(
        account.getId(), account.getCurrency(), account.getBalance(), Instant.now());
  }
}
