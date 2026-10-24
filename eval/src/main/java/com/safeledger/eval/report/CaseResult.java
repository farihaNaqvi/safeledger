package com.safeledger.eval.report;

import com.safeledger.eval.cases.EvalCase;
import com.safeledger.eval.score.Scorer;
import java.util.List;

public record CaseResult(
    EvalCase evalCase,
    List<Scorer.Failure> failures,
    List<Transcript> transcript,
    long inputTokens,
    long outputTokens) {

  /** One line of the conversation; {@code tools} are the tools called during an assistant turn. */
  public record Transcript(String role, String text, List<String> tools) {}

  public boolean passed() {
    return failures.isEmpty();
  }
}
