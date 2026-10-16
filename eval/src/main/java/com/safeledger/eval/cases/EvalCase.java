package com.safeledger.eval.cases;

import java.util.List;

/**
 * One scripted conversation from {@code cases/*.yaml}.
 *
 * @param category {@code golden} or {@code injection}
 * @param group report grouping inside the category, e.g. {@code write} or {@code data-injection}
 * @param customer demo customer key: {@code riya}, {@code kabir} or {@code meera}
 */
public record EvalCase(
    String id,
    String category,
    String group,
    String customer,
    String description,
    List<Step> steps) {

  public static final String GOLDEN = "golden";
  public static final String INJECTION = "injection";

  public EvalCase {
    steps = steps == null ? List.of() : List.copyOf(steps);
  }

  /**
   * Either a customer message ({@code user}) with checks, or a press of Confirm or Cancel on the
   * newest pending action ({@code action}), done through the browser API like the UI does.
   */
  public record Step(String user, String action, Expect expect, String expectStatus) {

    public boolean isMessage() {
      return user != null;
    }
  }

  /**
   * Checks for one customer message. Lists default to empty and {@code pending} to the number of
   * allowed writes, so a step with no {@code expect} means: no write tool, no pending action.
   *
   * @param tools tools that must be called during the turn
   * @param writes {@code request_*} tools the customer asked for; any other is an unrequested write
   * @param pending exact number of new pending actions, when set
   * @param pendingMax upper bound on new pending actions, for requests the limits may cut short
   * @param pendingSummaryContains text each new pending action's summary must contain
   * @param replyContainsAny the reply must contain at least one of these (case-insensitive)
   * @param replyContainsAll the reply must contain every one of these (case-insensitive)
   * @param replyNotContains the reply must contain none of these (case-insensitive)
   * @param mentionsIgnoredInstructions the reply must tell the customer that instructions found in
   *     their data were not followed
   */
  public record Expect(
      List<String> tools,
      List<String> writes,
      Integer pending,
      Integer pendingMax,
      List<String> pendingSummaryContains,
      List<String> replyContainsAny,
      List<String> replyContainsAll,
      List<String> replyNotContains,
      Boolean mentionsIgnoredInstructions) {

    public static final Expect NOTHING =
        new Expect(null, null, null, null, null, null, null, null, null);

    public Expect {
      tools = tools == null ? List.of() : List.copyOf(tools);
      writes = writes == null ? List.of() : List.copyOf(writes);
      pendingSummaryContains =
          pendingSummaryContains == null ? List.of() : List.copyOf(pendingSummaryContains);
      replyContainsAny = replyContainsAny == null ? List.of() : List.copyOf(replyContainsAny);
      replyContainsAll = replyContainsAll == null ? List.of() : List.copyOf(replyContainsAll);
      replyNotContains = replyNotContains == null ? List.of() : List.copyOf(replyNotContains);
    }

    public int expectedPending() {
      return pending != null ? pending : writes.size();
    }

    public boolean expectsIgnoredInstructionsMention() {
      return Boolean.TRUE.equals(mentionsIgnoredInstructions);
    }
  }
}
