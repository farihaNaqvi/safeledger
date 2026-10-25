package com.safeledger.bankmcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.safeledger.bankmcpserver.action.PendingAction;
import com.safeledger.bankmcpserver.action.PendingActionService;
import com.safeledger.bankmcpserver.identity.CallerIdentity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Generated, model-checked sequences against the real approval flow: random mixes of confirm,
 * cancel, concurrent double confirm, expiry and the expiry sweep, by the owner and by another
 * customer, over the real HTTP endpoints, database and (fake) bank. After every step the invariants
 * below must hold. A failure names the seed, which replays the exact sequence: {@code
 * -Dproperty.seed=... -Dproperty.sequences=1}. Larger runs: {@code -Dproperty.sequences=N}.
 */
class ApprovalInvariantsIT extends McpIntegrationTestBase {

  private static final Logger log = LoggerFactory.getLogger(ApprovalInvariantsIT.class);

  static final int SEQUENCES = Integer.getInteger("property.sequences", 300);
  static final long SEED = Long.getLong("property.seed", 20261025L);

  private static final Set<String> TERMINAL = Set.of("EXECUTED", "CANCELLED", "EXPIRED", "FAILED");

  enum Op {
    CONFIRM_OWNER,
    CONFIRM_OTHER_CUSTOMER,
    CANCEL_OWNER,
    CANCEL_OTHER_CUSTOMER,
    DOUBLE_CONFIRM,
    EXPIRE,
    SWEEP
  }

  @Autowired PendingActionService actions;

  @Test
  void approvalInvariantsHoldForGeneratedSequences() {
    Random seeds = new Random(SEED);
    int steps = 0;
    for (int i = 0; i < SEQUENCES; i++) {
      long seed = seeds.nextLong();
      try {
        steps += runSequence(new Random(seed));
      } catch (AssertionError e) {
        throw new AssertionError("sequence " + i + ", seed " + seed + ": " + e.getMessage(), e);
      }
    }
    log.info("Approval invariants held: sequences={} steps={} seed={}", SEQUENCES, steps, SEED);
  }

  private int runSequence(Random r) {
    UUID me = newCustomer();
    UUID other = newCustomer();
    String session = newSession();
    String otherSession = newSession();
    FakeBank.CustomerData data = bank.customer(me);
    bank.customer(other);
    CallerIdentity caller = new CallerIdentity(me, session);

    List<UUID> ids = new ArrayList<>();
    for (int i = 0, n = 1 + r.nextInt(3); i < n; i++) {
      BigDecimal amount = new BigDecimal((1 + r.nextInt(500)) + ".00");
      Map<String, Object> args =
          Map.of(
              "fromAccountId", data.savings.toString(),
              "payeeId", data.payee.toString(),
              "amount", amount.toPlainString());
      PendingAction a =
          actions.create(
              caller, "request_transfer", args, "Transfer " + amount, amount, Instant.now());
      ids.add(a.id());
    }

    Map<UUID, String> terminal = new HashMap<>();
    Set<UUID> ownerConfirmedWhilePending = new HashSet<>();
    Map<UUID, Integer> successfulConfirms = new HashMap<>();

    int steps = 6 + r.nextInt(10);
    for (int s = 0; s < steps; s++) {
      Op op = Op.values()[r.nextInt(Op.values().length)];
      UUID id = ids.get(r.nextInt(ids.size()));
      Map<UUID, String> before = statuses(ids);
      boolean pendingBefore = "PENDING".equals(before.get(id)) && !expired(id);

      switch (op) {
        case CONFIRM_OWNER -> {
          HttpResult res = confirm(me, session, id);
          if (res.status() == 200) {
            successfulConfirms.merge(id, 1, Integer::sum);
            assertThat(pendingBefore).as("confirm succeeded on a non-pending action").isTrue();
            ownerConfirmedWhilePending.add(id);
          }
        }
        case DOUBLE_CONFIRM -> {
          CompletableFuture<HttpResult> a =
              CompletableFuture.supplyAsync(() -> confirm(me, session, id));
          CompletableFuture<HttpResult> b =
              CompletableFuture.supplyAsync(() -> confirm(me, session, id));
          int ok = (a.join().status() == 200 ? 1 : 0) + (b.join().status() == 200 ? 1 : 0);
          assertThat(ok).as("two concurrent confirms both succeeded").isLessThanOrEqualTo(1);
          if (ok == 1) {
            successfulConfirms.merge(id, 1, Integer::sum);
            assertThat(pendingBefore).isTrue();
            ownerConfirmedWhilePending.add(id);
          }
        }
        case CONFIRM_OTHER_CUSTOMER -> {
          assertThat(confirm(other, otherSession, id).status()).isEqualTo(404);
          assertThat(statuses(ids))
              .as("another customer's confirm changed state")
              .isEqualTo(before);
        }
        case CANCEL_OTHER_CUSTOMER -> {
          assertThat(cancel(other, otherSession, id).status()).isEqualTo(404);
          assertThat(statuses(ids)).as("another customer's cancel changed state").isEqualTo(before);
        }
        case CANCEL_OWNER -> cancel(me, session, id);
        case EXPIRE ->
            jdbc.update(
                "update pending_action set expires_at = now() - interval '1 second'"
                    + " where id = ? and status = 'PENDING'",
                id);
        case SWEEP -> actions.expireOverdue();
      }

      Map<UUID, String> after = statuses(ids);
      for (UUID a : ids) {
        String status = after.get(a);
        String key =
            jdbc.queryForObject(
                "select idempotency_key from pending_action where id = ?", String.class, a);
        long bankCalls =
            bank.writesFor(me).stream().filter(w -> key.equals(w.idempotencyKey())).count();

        assertThat(bankCalls).as("bank called more than once for %s", a).isLessThanOrEqualTo(1);
        assertThat(status.equals("EXECUTED"))
            .as("EXECUTED must match a transfer at the bank for %s", a)
            .isEqualTo(data.transfersByKey.containsKey(key));
        if (status.equals("EXECUTED")) {
          assertThat(ownerConfirmedWhilePending)
              .as("executed without an owner confirm")
              .contains(a);
        }
        assertThat(successfulConfirms.getOrDefault(a, 0)).isLessThanOrEqualTo(1);
        if (terminal.containsKey(a)) {
          assertThat(status).as("terminal state changed for %s", a).isEqualTo(terminal.get(a));
        } else if (TERMINAL.contains(status)) {
          terminal.put(a, status);
        }
      }
    }
    return steps;
  }

  private Map<UUID, String> statuses(List<UUID> ids) {
    Map<UUID, String> out = new HashMap<>();
    for (UUID id : ids) {
      out.put(
          id,
          jdbc.queryForObject("select status from pending_action where id = ?", String.class, id));
    }
    return out;
  }

  private boolean expired(UUID id) {
    Boolean past =
        jdbc.queryForObject(
            "select expires_at <= now() from pending_action where id = ?", Boolean.class, id);
    return Boolean.TRUE.equals(past);
  }
}
