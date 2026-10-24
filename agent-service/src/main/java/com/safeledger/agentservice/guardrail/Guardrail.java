package com.safeledger.agentservice.guardrail;

/**
 * A content check applied to every customer message before the model sees it, and to every reply
 * before the customer sees it.
 */
public interface Guardrail {

  enum Source {
    INPUT,
    OUTPUT
  }

  /**
   * @param intervened true when the guardrail blocked or changed the text
   * @param blocked true when the text must not be used at all; false when it was only masked
   * @param text the text to use: the original, a masked version, or the blocked-message text
   */
  record Result(boolean intervened, boolean blocked, String text) {
    public static Result passed(String text) {
      return new Result(false, false, text);
    }

    public static Result masked(String text) {
      return new Result(true, false, text);
    }

    public static Result blocked(String text) {
      return new Result(true, true, text);
    }
  }

  Result check(Source source, String text);

  /** Name for logs and the API response, e.g. "bedrock" or "none". */
  String name();
}
