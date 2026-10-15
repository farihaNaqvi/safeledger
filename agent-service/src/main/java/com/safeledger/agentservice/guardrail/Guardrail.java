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
   * @param text the text to use: the original, a masked version, or the blocked-message text
   */
  record Result(boolean intervened, String text) {
    public static Result passed(String text) {
      return new Result(false, text);
    }
  }

  Result check(Source source, String text);

  /** Name for logs and the API response, e.g. "bedrock" or "none". */
  String name();
}
