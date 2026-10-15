package com.safeledger.bankmcpserver.bank;

import com.safeledger.bankmcpserver.common.CorrelationIdFilter;
import com.safeledger.bankmcpserver.common.Json;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * HTTP client for the mock bank API. Every call carries the verified customer ID as {@code
 * X-Customer-Id} and the current correlation ID; writes carry the pending action's idempotency key.
 */
@Component
public class BankClient {

  private static final String CUSTOMER_HEADER = "X-Customer-Id";
  private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

  private final RestClient.Builder builder;
  private final Environment environment;
  private volatile RestClient client;

  public BankClient(RestClient.Builder builder, Environment environment) {
    this.builder = builder;
    this.environment = environment;
  }

  public List<BankModels.Account> accounts(UUID customerId) {
    return Arrays.asList(
        get(customerId, "/customers/{id}/accounts", BankModels.Account[].class, customerId));
  }

  public BankModels.Balance balance(UUID customerId, UUID accountId) {
    return get(customerId, "/accounts/{id}/balance", BankModels.Balance.class, accountId);
  }

  public List<BankModels.Card> cards(UUID customerId) {
    return Arrays.asList(
        get(customerId, "/customers/{id}/cards", BankModels.Card[].class, customerId));
  }

  public List<BankModels.Payee> payees(UUID customerId) {
    return Arrays.asList(
        get(customerId, "/customers/{id}/payees", BankModels.Payee[].class, customerId));
  }

  public List<BankModels.Dispute> disputes(UUID customerId) {
    return Arrays.asList(
        get(customerId, "/customers/{id}/disputes", BankModels.Dispute[].class, customerId));
  }

  public BankModels.Transaction transaction(UUID customerId, UUID transactionId) {
    return get(customerId, "/transactions/{id}", BankModels.Transaction.class, transactionId);
  }

  public List<BankModels.Transaction> transactions(
      UUID customerId, UUID accountId, BankModels.TransactionQuery query) {
    BankModels.Transaction[] result =
        client()
            .get()
            .uri(
                b -> {
                  UriBuilder uri = b.path("/accounts/{id}/transactions");
                  optional(uri, "from", query.from());
                  optional(uri, "to", query.to());
                  optional(uri, "merchant", query.merchant());
                  optional(uri, "minAmount", query.minAmount());
                  optional(uri, "maxAmount", query.maxAmount());
                  uri.queryParam("limit", query.limit());
                  return uri.build(accountId);
                })
            .headers(h -> headers(h, customerId, null))
            .retrieve()
            .onStatus(HttpStatusCode::isError, BankClient::raise)
            .body(BankModels.Transaction[].class);
    return result == null ? List.of() : Arrays.asList(result);
  }

  public BankModels.Card freezeCard(UUID customerId, UUID cardId) {
    return post(customerId, null, "/cards/{id}/freeze", null, BankModels.Card.class, cardId);
  }

  public BankModels.Card unfreezeCard(UUID customerId, UUID cardId) {
    return post(customerId, null, "/cards/{id}/unfreeze", null, BankModels.Card.class, cardId);
  }

  public BankModels.Transfer transfer(
      UUID customerId,
      String idempotencyKey,
      UUID fromAccountId,
      UUID payeeId,
      BigDecimal amount,
      String note) {
    Map<String, Object> body =
        note == null
            ? Map.of("fromAccountId", fromAccountId, "payeeId", payeeId, "amount", amount)
            : Map.of(
                "fromAccountId", fromAccountId, "payeeId", payeeId, "amount", amount, "note", note);
    return post(customerId, idempotencyKey, "/transfers", body, BankModels.Transfer.class);
  }

  public BankModels.Dispute dispute(
      UUID customerId, String idempotencyKey, UUID transactionId, String reason) {
    return post(
        customerId,
        idempotencyKey,
        "/disputes",
        Map.of("transactionId", transactionId, "reason", reason),
        BankModels.Dispute.class);
  }

  private <T> T get(UUID customerId, String path, Class<T> type, Object... vars) {
    return client()
        .get()
        .uri(path, vars)
        .headers(h -> headers(h, customerId, null))
        .retrieve()
        .onStatus(HttpStatusCode::isError, BankClient::raise)
        .body(type);
  }

  private <T> T post(
      UUID customerId,
      String idempotencyKey,
      String path,
      Object body,
      Class<T> type,
      Object... vars) {
    RestClient.RequestBodySpec request =
        client()
            .post()
            .uri(path, vars)
            .headers(h -> headers(h, customerId, idempotencyKey))
            .contentType(MediaType.APPLICATION_JSON);
    if (body != null) {
      request.body(body);
    }
    return request.retrieve().onStatus(HttpStatusCode::isError, BankClient::raise).body(type);
  }

  private static void headers(HttpHeaders h, UUID customerId, String idempotencyKey) {
    h.set(CUSTOMER_HEADER, customerId.toString());
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    if (correlationId != null) {
      h.set(CorrelationIdFilter.HEADER, correlationId);
    }
    if (idempotencyKey != null) {
      h.set(IDEMPOTENCY_HEADER, idempotencyKey);
    }
  }

  private static void optional(UriBuilder uri, String name, Object value) {
    if (value != null && !(value instanceof String s && s.isBlank())) {
      uri.queryParam(name, value);
    }
  }

  private static void raise(HttpRequest request, ClientHttpResponse response) throws IOException {
    int status = response.getStatusCode().value();
    String code = null;
    String detail = "Bank API returned " + status;
    try {
      byte[] raw = response.getBody().readAllBytes();
      if (raw.length > 0) {
        Map<?, ?> problem = Json.read(new String(raw, StandardCharsets.UTF_8), Map.class);
        code = problem.get("code") instanceof String c ? c : null;
        detail = problem.get("detail") instanceof String d ? d : detail;
      }
    } catch (RuntimeException ignored) {
      // Not a problem document; keep the generic detail.
    }
    throw new BankApiException(status, code, detail);
  }

  /**
   * Built on first use so tests can point the base URL at the running server's own port, which is
   * only known after startup.
   */
  private RestClient client() {
    RestClient current = client;
    if (current == null) {
      synchronized (this) {
        if (client == null) {
          client =
              builder.baseUrl(environment.getRequiredProperty("safeledger.bank.base-url")).build();
        }
        current = client;
      }
    }
    return current;
  }
}
