package com.safeledger.mockbankapi.dispute;

import com.safeledger.mockbankapi.common.Headers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DisputeController {

  public record CreateDisputeRequest(
      @NotNull UUID transactionId, @NotBlank @Size(max = 500) String reason) {}

  public record DisputeResponse(
      UUID id, UUID transactionId, String reason, Dispute.Status status, Instant createdAt) {
    static DisputeResponse from(Dispute d) {
      return new DisputeResponse(
          d.getId(), d.getTransactionId(), d.getReason(), d.getStatus(), d.getCreatedAt());
    }
  }

  private final DisputeService disputeService;
  private final DisputeRepository disputes;

  public DisputeController(DisputeService disputeService, DisputeRepository disputes) {
    this.disputeService = disputeService;
    this.disputes = disputes;
  }

  /** 201 when created; 200 with {@code Idempotent-Replayed: true} for a repeat of the same key. */
  @PostMapping("/disputes")
  public ResponseEntity<DisputeResponse> create(
      @RequestHeader(Headers.CUSTOMER_ID) UUID caller,
      @RequestHeader(Headers.IDEMPOTENCY_KEY) String idempotencyKey,
      @Valid @RequestBody CreateDisputeRequest body) {
    DisputeService.Result result =
        disputeService.create(caller, idempotencyKey, body.transactionId(), body.reason());
    return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
        .header(Headers.IDEMPOTENT_REPLAYED, String.valueOf(result.replayed()))
        .body(DisputeResponse.from(result.dispute()));
  }

  @GetMapping("/customers/{customerId}/disputes")
  public List<DisputeResponse> list(
      @PathVariable UUID customerId, @RequestHeader(Headers.CUSTOMER_ID) UUID caller) {
    Headers.requireSameCustomer(customerId, caller);
    return disputes.findByCustomerIdOrderByCreatedAtDescIdAsc(caller).stream()
        .map(DisputeResponse::from)
        .toList();
  }
}
