package com.safeledger.mockbankapi.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps every error to a problem detail with a machine readable {@code code} property. */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> handleApi(ApiException e) {
    return problem(e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException e) {
    return switch (e.getHeaderName()) {
      case Headers.CUSTOMER_ID ->
          problem(
              HttpStatus.UNAUTHORIZED,
              "MISSING_CUSTOMER_ID",
              Headers.CUSTOMER_ID + " header is required");
      case Headers.IDEMPOTENCY_KEY ->
          problem(
              HttpStatus.BAD_REQUEST,
              "MISSING_IDEMPOTENCY_KEY",
              Headers.IDEMPOTENCY_KEY + " header is required");
      default ->
          problem(
              HttpStatus.BAD_REQUEST, "MISSING_HEADER", e.getHeaderName() + " header is required");
    };
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid value for " + e.getName());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ProblemDetail> handleMissingParam(MissingServletRequestParameterException e) {
    return problem(
        HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", e.getParameterName() + " is required");
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException e) {
    String detail =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + " " + f.getDefaultMessage())
            .sorted()
            .reduce((a, b) -> a + "; " + b)
            .orElse("Request body is invalid");
    return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException e) {
    return problem(HttpStatus.BAD_REQUEST, "MALFORMED_BODY", "Request body could not be read");
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail) {
    log.info("Request rejected: status={} code={}", status.value(), code);
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setProperty("code", code);
    return ResponseEntity.status(status).body(body);
  }
}
