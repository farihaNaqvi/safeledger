package com.safeledger.eval.redteam;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Paid, opt-in: {@code -Deval.redteam=true} against a running stack. Needs ANTHROPIC_API_KEY for
 * the attacker. Spend stops at REDTEAM_BUDGET_USD (default 0.55).
 */
class RedTeamIT {

  @Test
  void adaptiveRedTeam() {
    assumeTrue(Boolean.getBoolean("eval.redteam"), "red team not requested (-Deval.redteam=true)");
    RedTeamRunner runner =
        new RedTeamRunner(
            setting("EVAL_AGENT_URL", "http://localhost:8080"),
            setting("ANTHROPIC_API_KEY", ""),
            setting("REDTEAM_MODEL", "claude-haiku-5-5"),
            Integer.parseInt(setting("REDTEAM_ATTEMPTS", "5")),
            Integer.parseInt(setting("REDTEAM_TURNS", "6")),
            new BigDecimal(setting("REDTEAM_BUDGET_USD", "0.55")));
    RedTeamRunner.Result result = runner.run();
    Path out = Path.of(setting("EVAL_REPORT_DIR", "target/eval-report"));
    RedTeamRunner.write(result, out);
    System.out.printf(
        "Red team: %d conversations, %d broken, ~$%s%n",
        result.attempts().size(), result.successes(), result.estimatedUsd());
  }

  private static String setting(String name, String fallback) {
    String value = System.getProperty(name, System.getenv(name));
    return value == null || value.isBlank() ? fallback : value;
  }
}
