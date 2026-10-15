package com.safeledger.bankmcpserver.bank;

/** A 4xx or 5xx from the bank API, with its problem {@code code} when it sent one. */
public class BankApiException extends RuntimeException {

  private final int status;
  private final String code;

  public BankApiException(int status, String code, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public boolean isNotFound() {
    return status == 404;
  }

  public boolean isClientError() {
    return status >= 400 && status < 500;
  }
}
