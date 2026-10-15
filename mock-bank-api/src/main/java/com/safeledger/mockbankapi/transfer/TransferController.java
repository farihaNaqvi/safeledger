package com.safeledger.mockbankapi.transfer;

import com.safeledger.mockbankapi.common.Headers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

  public record CreateTransferRequest(
      @NotNull UUID fromAccountId,
      @NotNull UUID payeeId,
      @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
      @Size(max = 200) String note) {}

  public record TransferResponse(
      UUID id,
      UUID fromAccountId,
      UUID payeeId,
      BigDecimal amount,
      String currency,
      String note,
      Transfer.Status status,
      Instant createdAt) {
    static TransferResponse from(Transfer t) {
      return new TransferResponse(
          t.getId(),
          t.getFromAccountId(),
          t.getPayeeId(),
          t.getAmount(),
          "INR",
          t.getNote(),
          t.getStatus(),
          t.getCreatedAt());
    }
  }

  private final TransferService transferService;

  public TransferController(TransferService transferService) {
    this.transferService = transferService;
  }

  /**
   * 201 when the transfer runs now; 200 with {@code Idempotent-Replayed: true} when the same key
   * and body were already processed, in which case nothing is debited again.
   */
  @PostMapping("/transfers")
  public ResponseEntity<TransferResponse> create(
      @RequestHeader(Headers.CUSTOMER_ID) UUID caller,
      @RequestHeader(Headers.IDEMPOTENCY_KEY) String idempotencyKey,
      @Valid @RequestBody CreateTransferRequest body) {
    TransferService.Result result =
        transferService.create(
            caller,
            idempotencyKey,
            new TransferService.Request(
                body.fromAccountId(), body.payeeId(), body.amount(), body.note()));
    return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
        .header(Headers.IDEMPOTENT_REPLAYED, String.valueOf(result.replayed()))
        .body(TransferResponse.from(result.transfer()));
  }
}
