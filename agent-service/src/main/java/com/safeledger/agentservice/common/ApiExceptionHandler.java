package com.safeledger.agentservice.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> handleApi(ApiException e) {
    return problem(e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ProblemDetail> handleBadRequest(Exception e) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is not valid");
  }

  @ExceptionHandler(ResourceAccessException.class)
  ResponseEntity<ProblemDetail> handleBankUnavailable(ResourceAccessException e) {
    log.warn("Bank service unreachable: {}", e.getMessage());
    return problem(HttpStatus.SERVICE_UNAVAILABLE, BANK_UNAVAILABLE, BANK_UNAVAILABLE_DETAIL);
  }

  public static final String BANK_UNAVAILABLE = "BANK_UNAVAILABLE";
  public static final String BANK_UNAVAILABLE_DETAIL =
      "The bank service is not available right now. Please try again shortly.";

  static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setProperty("code", code);
    return ResponseEntity.status(status).body(body);
  }
}
