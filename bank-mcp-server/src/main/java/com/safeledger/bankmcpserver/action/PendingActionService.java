package com.safeledger.bankmcpserver.action;

import com.safeledger.bankmcpserver.audit.AuditLog;
import com.safeledger.bankmcpserver.bank.BankApiException;
import com.safeledger.bankmcpserver.bank.BankClient;
import com.safeledger.bankmcpserver.common.Json;
import com.safeledger.bankmcpserver.config.SafeLedgerProperties;
import com.safeledger.bankmcpserver.identity.CallerIdentity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Lifecycle of a pending action: created by a write tool, then confirmed (and executed once),
 * cancelled, or expired. Confirm and cancel are reachable only through the internal HTTP endpoints,
 * never through an MCP tool.
 */
@Service
public class PendingActionService {

  private static final Logger log = LoggerFactory.getLogger(PendingActionService.class);

  public enum ConfirmOutcome {
    EXECUTED,
    FAILED,
    NOT_FOUND,
    NOT_PENDING,
    EXPIRED
  }

  public record ConfirmResult(ConfirmOutcome outcome, PendingAction action) {}

  private final PendingActionRepository repository;
  private final BankClient bank;
  private final AuditLog audit;
  private final SafeLedgerProperties properties;

  public PendingActionService(
      PendingActionRepository repository,
      BankClient bank,
      AuditLog audit,
      SafeLedgerProperties properties) {
    this.repository = repository;
    this.bank = bank;
    this.audit = audit;
    this.properties = properties;
  }

  /** Free-text arguments; a request that differs only in these is the same request. */
  private static final List<String> FREE_TEXT = List.of("reason", "note");

  /** True if the same request (same tool and target, any wording) is already waiting. */
  public boolean hasOpenDuplicate(
      CallerIdentity caller, String toolName, Map<String, Object> arguments, Instant now) {
    Map<String, Object> target = withoutFreeText(arguments);
    return repository.findOpen(caller.customerId(), toolName, now).stream()
        .anyMatch(a -> withoutFreeText(a.arguments()).equals(target));
  }

  private static Map<String, Object> withoutFreeText(Map<String, Object> arguments) {
    Map<String, Object> copy = new HashMap<>(arguments);
    FREE_TEXT.forEach(copy::remove);
    return copy;
  }

  public PendingAction create(
      CallerIdentity caller,
      String toolName,
      Map<String, Object> arguments,
      String summary,
      BigDecimal amount,
      Instant now) {
    PendingAction action =
        new PendingAction(
            UUID.randomUUID(),
            caller.customerId(),
            caller.sessionId(),
            toolName,
            arguments,
            summary,
            PendingAction.Status.PENDING,
            "act-" + UUID.randomUUID(),
            amount,
            now,
            now.plus(properties.actions().ttl()),
            null,
            null);
    repository.insert(action);
    audit.action(AuditLog.EventType.ACTION_CREATED, caller, toolName, action.id(), "PENDING");
    return action;
  }

  /**
   * Runs the action at most once. Only the request that flips PENDING to CONFIRMED executes it; any
   * other concurrent or repeated confirm sees it is no longer pending.
   */
  public ConfirmResult confirm(CallerIdentity caller, UUID actionId) {
    Instant now = Instant.now();
    var found = repository.findOwned(actionId, caller.customerId());
    if (found.isEmpty()) {
      audit.action(AuditLog.EventType.ACTION_REJECTED, caller, null, actionId, "NOT_FOUND");
      return new ConfirmResult(ConfirmOutcome.NOT_FOUND, null);
    }
    PendingAction action = found.get();

    if (!repository.markConfirmed(actionId, caller.customerId(), now)) {
      PendingAction current = repository.findOwned(actionId, caller.customerId()).orElseThrow();
      if (current.status() == PendingAction.Status.PENDING && !current.expiresAt().isAfter(now)) {
        // Overdue but the sweep has not run yet: expire it (and any others) now.
        expireOverdue();
        current = repository.findOwned(actionId, caller.customerId()).orElseThrow();
      }
      ConfirmOutcome outcome =
          current.status() == PendingAction.Status.EXPIRED
              ? ConfirmOutcome.EXPIRED
              : ConfirmOutcome.NOT_PENDING;
      audit.action(
          AuditLog.EventType.ACTION_REJECTED, caller, action.toolName(), actionId, outcome.name());
      return new ConfirmResult(outcome, current);
    }
    audit.action(
        AuditLog.EventType.ACTION_CONFIRMED, caller, action.toolName(), actionId, "CONFIRMED");

    PendingAction.Status finalStatus;
    String result;
    try {
      Object bankResult = execute(caller, action);
      finalStatus = PendingAction.Status.EXECUTED;
      result = Json.write(Map.of("ok", true, "bank", bankResult));
    } catch (BankApiException e) {
      finalStatus = PendingAction.Status.FAILED;
      result =
          Json.write(
              Map.of(
                  "ok",
                  false,
                  "status",
                  e.status(),
                  "code",
                  e.code() == null ? "BANK_ERROR" : e.code(),
                  "detail",
                  e.getMessage()));
      log.warn("Action failed at the bank: actionId={} status={}", actionId, e.status());
    } catch (RuntimeException e) {
      // The bank may or may not have applied it; the stored idempotency key makes a manual retry
      // safe, so record it as failed and let a person reconcile.
      finalStatus = PendingAction.Status.FAILED;
      result = Json.write(Map.of("ok", false, "code", "BANK_UNAVAILABLE"));
      log.error("Action execution error: actionId={}", actionId, e);
    }
    repository.markFinished(actionId, finalStatus, result, Instant.now());
    audit.action(
        finalStatus == PendingAction.Status.EXECUTED
            ? AuditLog.EventType.ACTION_EXECUTED
            : AuditLog.EventType.ACTION_FAILED,
        caller,
        action.toolName(),
        actionId,
        finalStatus.name());
    PendingAction finished = repository.findOwned(actionId, caller.customerId()).orElseThrow();
    return new ConfirmResult(
        finalStatus == PendingAction.Status.EXECUTED
            ? ConfirmOutcome.EXECUTED
            : ConfirmOutcome.FAILED,
        finished);
  }

  /** Returns the action if it was cancelled now; empty if not found or no longer pending. */
  public CancelResult cancel(CallerIdentity caller, UUID actionId) {
    var found = repository.findOwned(actionId, caller.customerId());
    if (found.isEmpty()) {
      return new CancelResult(false, null);
    }
    boolean cancelled = repository.markCancelled(actionId, caller.customerId());
    audit.action(
        cancelled ? AuditLog.EventType.ACTION_CANCELLED : AuditLog.EventType.ACTION_REJECTED,
        caller,
        found.get().toolName(),
        actionId,
        cancelled ? "CANCELLED" : "NOT_PENDING");
    return new CancelResult(
        cancelled, repository.findOwned(actionId, caller.customerId()).orElseThrow());
  }

  public record CancelResult(boolean cancelled, PendingAction action) {}

  public List<PendingAction> listForSession(CallerIdentity caller) {
    return repository.findBySession(caller.customerId(), caller.sessionId());
  }

  /** Expires every overdue pending action and audits exactly the ones this call changed. */
  public int expireOverdue() {
    List<UUID> expired = repository.expireOverdue(Instant.now());
    expired.forEach(audit::actionExpired);
    return expired.size();
  }

  private Object execute(CallerIdentity caller, PendingAction action) {
    UUID customer = caller.customerId();
    return switch (action.toolName()) {
      case "request_card_freeze" ->
          bank.freezeCard(customer, UUID.fromString(action.argument("cardId")));
      case "request_card_unfreeze" ->
          bank.unfreezeCard(customer, UUID.fromString(action.argument("cardId")));
      case "request_dispute" ->
          bank.dispute(
              customer,
              action.idempotencyKey(),
              UUID.fromString(action.argument("transactionId")),
              action.argument("reason"));
      case "request_transfer" ->
          bank.transfer(
              customer,
              action.idempotencyKey(),
              UUID.fromString(action.argument("fromAccountId")),
              UUID.fromString(action.argument("payeeId")),
              action.amount(),
              action.argument("note"));
      default -> throw new IllegalStateException("Unknown action tool " + action.toolName());
    };
  }
}
