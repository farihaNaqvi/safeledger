package com.safeledger.agentservice.common;

import org.springframework.http.HttpStatus;

/** An error returned to the browser as a problem document with a stable {@code code}. */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }
}
