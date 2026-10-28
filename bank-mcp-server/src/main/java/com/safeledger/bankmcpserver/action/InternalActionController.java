package com.safeledger.bankmcpserver.action;

import com.safeledger.bankmcpserver.common.Json;
import com.safeledger.bankmcpserver.identity.CallerIdentity;
import com.safeledger.bankmcpserver.identity.IdentityFilter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Confirm and cancel, for agent-service only. These are plain HTTP endpoints, not MCP tools, so the
 * model has no way to call them. {@link IdentityFilter} has already verified the caller.
 */
@RestController
public class InternalActionController {

  /**
   * {@code summary} is built by the server from stored values only. {@code payeeNote} is the note
   * the customer typed when confirming a transfer; null until then.
   */
  public record ActionView(
      UUID id,
      String tool,
      String summary,
      String payeeNote,
      PendingAction.Status status,
      Instant createdAt,
      Instant expiresAt,
      Instant executedAt,
      Object result) {
    static ActionView from(PendingAction a) {
      return new ActionView(
          a.id(),
          a.toolName(),
          a.summary(),
          a.argument("note"),
          a.status(),
          a.createdAt(),
          a.expiresAt(),
          a.executedAt(),
          a.result() == null ? null : Json.read(a.result(), Map.class));
    }
  }

  public record Problem(String code, String detail, PendingAction.Status status) {}

  public record ConfirmRequest(String payeeNote) {}

  private final PendingActionService service;

  public InternalActionController(PendingActionService service) {
    this.service = service;
  }

  @PostMapping("/internal/actions/{id}/confirm")
  public ResponseEntity<?> confirm(
      @PathVariable UUID id,
      @RequestAttribute(IdentityFilter.ATTRIBUTE) CallerIdentity caller,
      @RequestBody(required = false) ConfirmRequest body) {
    PendingActionService.ConfirmResult result =
        service.confirm(caller, id, body == null ? null : body.payeeNote());
    return switch (result.outcome()) {
      case EXECUTED, FAILED -> ResponseEntity.ok(ActionView.from(result.action()));
      case NOT_FOUND ->
          ResponseEntity.status(HttpStatus.NOT_FOUND)
              .body(new Problem("NOT_FOUND", "Action not found", null));
      case EXPIRED ->
          ResponseEntity.status(HttpStatus.GONE)
              .body(
                  new Problem(
                      "ACTION_EXPIRED", "This request has expired", result.action().status()));
      case NOT_PENDING ->
          ResponseEntity.status(HttpStatus.CONFLICT).body(notPending(result.action()));
      case INVALID_NOTE ->
          ResponseEntity.badRequest()
              .body(
                  new Problem(
                      "INVALID_NOTE",
                      "A payee note goes only on a transfer, on one line, up to "
                          + PendingActionService.NOTE_MAX
                          + " characters",
                      result.action().status()));
    };
  }

  @PostMapping("/internal/actions/{id}/cancel")
  public ResponseEntity<?> cancel(
      @PathVariable UUID id, @RequestAttribute(IdentityFilter.ATTRIBUTE) CallerIdentity caller) {
    PendingActionService.CancelResult result = service.cancel(caller, id);
    if (result.action() == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(new Problem("NOT_FOUND", "Action not found", null));
    }
    if (!result.cancelled()) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(notPending(result.action()));
    }
    return ResponseEntity.ok(ActionView.from(result.action()));
  }

  private static Problem notPending(PendingAction a) {
    if (a.status() == PendingAction.Status.SUPERSEDED) {
      return new Problem(
          "ACTION_SUPERSEDED", "This request was replaced by a newer one", a.status());
    }
    return new Problem(
        "ACTION_NOT_PENDING",
        "This request was already " + a.status().name().toLowerCase(),
        a.status());
  }

  /** The caller's actions in their current session, newest first. */
  @GetMapping("/internal/actions")
  public List<ActionView> list(@RequestAttribute(IdentityFilter.ATTRIBUTE) CallerIdentity caller) {
    return service.listForSession(caller).stream().map(ActionView::from).toList();
  }
}
