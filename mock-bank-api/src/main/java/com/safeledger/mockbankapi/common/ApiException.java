package com.safeledger.mockbankapi.common;

import org.springframework.http.HttpStatus;

/** An error the API reports to the caller as an RFC 9457 problem with a stable {@code code}. */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  /**
   * Same response whether the resource does not exist or belongs to another customer, so callers
   * cannot probe for other customers' IDs.
   */
  public static ApiException notFound(String resource) {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", resource + " not found");
  }

  public static ApiException badRequest(String code, String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, code, message);
  }

  public static ApiException unprocessable(String code, String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }

  public static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }
}
