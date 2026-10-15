package com.safeledger.mockbankapi.common;

import java.util.UUID;

/** Request headers the bank API understands, plus the ownership check on customer paths. */
public final class Headers {

  /**
   * Identity of the calling customer. A real bank would take this from a verified token; for this
   * mock the caller (the MCP server) is trusted to send it.
   */
  public static final String CUSTOMER_ID = "X-Customer-Id";

  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
  public static final String CORRELATION_ID = "X-Correlation-Id";

  private Headers() {}

  /** A customer may only read their own {@code /customers/{id}/...} resources. */
  public static void requireSameCustomer(UUID pathCustomerId, UUID callerCustomerId) {
    if (!pathCustomerId.equals(callerCustomerId)) {
      throw ApiException.notFound("Customer");
    }
  }
}
