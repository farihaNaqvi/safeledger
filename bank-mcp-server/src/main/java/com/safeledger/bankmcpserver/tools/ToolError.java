package com.safeledger.bankmcpserver.tools;

/**
 * An expected failure the model should explain to the customer (limit reached, unknown card, and so
 * on). Returned as an MCP error result with this message, never as a stack trace.
 */
public class ToolError extends RuntimeException {

  private final String code;

  public ToolError(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
