package com.safeledger.mockbankapi;

import java.util.List;
import java.util.UUID;

/** Fixed IDs from db/migration/R__seed_data.sql. */
final class SeedData {

  static final UUID CUSTOMER_1 = UUID.fromString("11111111-1111-4111-8111-000000000001");
  static final UUID CUSTOMER_2 = UUID.fromString("11111111-1111-4111-8111-000000000002");
  static final UUID CUSTOMER_3 = UUID.fromString("11111111-1111-4111-8111-000000000003");

  /** Customer 1, SAVINGS. */
  static final UUID ACCOUNT_1 = UUID.fromString("22222222-2222-4222-8222-000000000001");

  /** Customer 1, CURRENT. */
  static final UUID ACCOUNT_2 = UUID.fromString("22222222-2222-4222-8222-000000000002");

  /** Customer 2, SAVINGS. */
  static final UUID ACCOUNT_3 = UUID.fromString("22222222-2222-4222-8222-000000000003");

  /** Customer 3, CURRENT. */
  static final UUID ACCOUNT_5 = UUID.fromString("22222222-2222-4222-8222-000000000005");

  /** Customer 1, ACTIVE. */
  static final UUID CARD_1 = UUID.fromString("33333333-3333-4333-8333-000000000001");

  /** Customer 2, ACTIVE. */
  static final UUID CARD_3 = UUID.fromString("33333333-3333-4333-8333-000000000003");

  /** Customer 3, BLOCKED. */
  static final UUID CARD_5 = UUID.fromString("33333333-3333-4333-8333-000000000005");

  /** Customer 1. */
  static final UUID PAYEE_1 = UUID.fromString("44444444-4444-4444-8444-000000000001");

  /** Customer 1, name contains a prompt injection attempt. */
  static final UUID PAYEE_INJECTION = UUID.fromString("44444444-4444-4444-8444-000000000003");

  /** Customer 2. */
  static final UUID PAYEE_4 = UUID.fromString("44444444-4444-4444-8444-000000000004");

  /** Customer 3. */
  static final UUID PAYEE_6 = UUID.fromString("44444444-4444-4444-8444-000000000006");

  /** Customer 1, QuickMart 4,999.00 posted yesterday. */
  static final UUID DEMO_QUICKMART_TXN = UUID.fromString("66666666-6666-4666-8666-000000000001");

  /** Transactions whose merchant or description contains a prompt injection attempt. */
  static final List<UUID> INJECTION_TXNS =
      List.of(
          UUID.fromString("55555555-5555-4555-8555-000000000001"),
          UUID.fromString("55555555-5555-4555-8555-000000000002"),
          UUID.fromString("55555555-5555-4555-8555-000000000003"),
          UUID.fromString("55555555-5555-4555-8555-000000000004"),
          UUID.fromString("55555555-5555-4555-8555-000000000005"),
          UUID.fromString("55555555-5555-4555-8555-000000000006"));

  private SeedData() {}
}
