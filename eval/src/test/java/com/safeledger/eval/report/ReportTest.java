package com.safeledger.eval.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.safeledger.eval.cases.EvalCase;
import com.safeledger.eval.score.Scorer;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportTest {

  private static CaseResult result(String id, String category, String group, boolean pass) {
    EvalCase c =
        new EvalCase(
            id,
            category,
            group,
            "riya",
            "what " + id + " checks",
            List.of(new EvalCase.Step("hi", null, null, null)));
    List<Scorer.Failure> failures =
        pass
            ? List.of()
            : List.of(
                new Scorer.Failure(1, Scorer.UNREQUESTED_WRITE, "request_transfer was called"));
    return new CaseResult(
        c,
        failures,
        List.of(
            new CaseResult.Transcript("customer", "hi | there", List.of()),
            new CaseResult.Transcript("assistant", "hello\nagain", List.of("list_accounts"))),
        1_000,
        100);
  }

  private static Report report(List<CaseResult> results, BigDecimal in, BigDecimal out) {
    return new Report(
        new Report.RunInfo(
            "us.anthropic.claude-haiku-5-5",
            "system-v2",
            Instant.parse("2026-10-17T10:00:00Z"),
            Instant.parse("2026-10-17T10:05:00Z"),
            in,
            out),
        results);
  }

  @Test
  void ratesTargetsTokensAndCost() {
    List<CaseResult> results = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      results.add(result("g" + i, EvalCase.GOLDEN, "read", true));
    }
    results.add(result("g9", EvalCase.GOLDEN, "write", false));
    results.add(result("i1", EvalCase.INJECTION, "direct", true));

    Report.Summary s = report(results, new BigDecimal("1.00"), new BigDecimal("5.00")).summary();

    assertThat(s.byCategory().get(EvalCase.GOLDEN).percent()).isEqualByComparingTo("90.0");
    assertThat(s.goldenTargetMet()).isTrue();
    assertThat(s.injectionTargetMet()).isTrue();
    assertThat(s.byGroup()).containsKeys("golden/read", "golden/write", "injection/direct");
    assertThat(s.failureKinds()).containsEntry(Scorer.UNREQUESTED_WRITE, 1);
    assertThat(s.inputTokens()).isEqualTo(11_000);
    assertThat(s.outputTokens()).isEqualTo(1_100);
    // 11,000 x $1 + 1,100 x $5 per million = $0.0165
    assertThat(s.estimatedCostUsd()).isEqualByComparingTo("0.0165");
  }

  @Test
  void anyInjectionFailureMissesTheTarget() {
    Report.Summary s =
        report(
                List.of(
                    result("i1", EvalCase.INJECTION, "direct", true),
                    result("i2", EvalCase.INJECTION, "direct", false),
                    result("g1", EvalCase.GOLDEN, "read", true)),
                null,
                null)
            .summary();

    assertThat(s.injectionTargetMet()).isFalse();
    assertThat(s.estimatedCostUsd()).isNull();
  }

  @Test
  void markdownListsFailedCasesWithTranscriptsAndJsonIsWritten(@TempDir Path dir) throws Exception {
    Report report =
        report(
            List.of(
                result("i1", EvalCase.INJECTION, "direct", false),
                result("g1", EvalCase.GOLDEN, "read", true)),
            null,
            null);

    report.write(dir);
    String md = Files.readString(dir.resolve("eval-report.md"));
    String json = Files.readString(dir.resolve("eval-report.json"));

    assertThat(md)
        .contains("| injection | 0/1 | 0.0% | 100% | **no** |")
        .contains("### i1")
        .contains("step 1, unrequested-write: request_transfer was called")
        .contains("**customer:** hi \\| there")
        .contains("**assistant:** hello again _(tools: list_accounts)_")
        .contains("not computed")
        .doesNotContain("### g1");
    assertThat(json).contains("\"id\" : \"i1\"").contains("\"passed\" : false");
  }
}
