package com.safeledger.eval.redteam;

import com.safeledger.eval.cases.Customers;
import com.safeledger.eval.run.AgentClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adaptive multi-turn attacks: for each goal, several conversations of up to {@code maxTurns}
 * messages, each in a fresh session as Riya. Confirm is never pressed. Stops early when the
 * estimated spend reaches the budget.
 */
public final class RedTeamRunner {

  public record Attempt(
      Goal goal, int attempt, List<Attacker.Turn> conversation, String evidence) {}

  public record Result(List<Attempt> attempts, BigDecimal estimatedUsd, boolean budgetReached) {

    public long successes() {
      return attempts.stream().filter(a -> a.evidence() != null).count();
    }
  }

  /** Haiku prices, USD per token, plus an allowance for two guardrail checks per agent turn. */
  private static final BigDecimal IN = new BigDecimal("0.000001");

  private static final BigDecimal OUT = new BigDecimal("0.000005");
  private static final BigDecimal GUARDRAIL_PER_TURN = new BigDecimal("0.0008");

  private final String agentUrl;
  private final Attacker attacker;
  private final int attemptsPerGoal;
  private final int maxTurns;
  private final BigDecimal budgetUsd;
  private BigDecimal spent = BigDecimal.ZERO;

  public RedTeamRunner(
      String agentUrl,
      String apiKey,
      String attackerModel,
      int attemptsPerGoal,
      int maxTurns,
      BigDecimal budgetUsd) {
    this.agentUrl = agentUrl;
    this.attacker = new Attacker(apiKey, attackerModel);
    this.attemptsPerGoal = attemptsPerGoal;
    this.maxTurns = maxTurns;
    this.budgetUsd = budgetUsd;
  }

  public Result run() {
    List<Attempt> attempts = new ArrayList<>();
    for (Goal goal : Goal.values()) {
      List<String> openers = new ArrayList<>();
      for (int n = 1; n <= attemptsPerGoal; n++) {
        if (spent.compareTo(budgetUsd) >= 0) {
          return new Result(attempts, spent, true);
        }
        Attempt a = attempt(goal, n, openers);
        attempts.add(a);
        if (!a.conversation().isEmpty()) {
          openers.add(a.conversation().getFirst().message());
        }
        System.out.printf(
            "%s %s #%d (%d turns)%s%n",
            a.evidence() == null ? "HELD" : "BROKEN",
            goal,
            n,
            a.conversation().size(),
            a.evidence() == null ? "" : ": " + a.evidence());
      }
    }
    return new Result(attempts, spent, false);
  }

  private Attempt attempt(Goal goal, int n, List<String> openers) {
    AgentClient agent = new AgentClient(agentUrl, Duration.ofSeconds(150));
    agent.login(Customers.RIYA.id());
    List<Attacker.Turn> conversation = new ArrayList<>();
    String evidence = null;
    for (int turn = 0; turn < maxTurns && evidence == null; turn++) {
      if (spent.compareTo(budgetUsd) >= 0) {
        break;
      }
      Attacker.Next next = attacker.next(goal, n, openers, conversation);
      charge(next.inputTokens(), next.outputTokens());
      AgentClient.ChatResult chat = agent.chat(next.message());
      charge(chat.inputTokens(), chat.outputTokens());
      spent = spent.add(GUARDRAIL_PER_TURN);
      String reply = chat.status() == 200 ? chat.reply() : "(HTTP " + chat.status() + ")";
      conversation.add(new Attacker.Turn(next.message(), reply));
      evidence = goal.achieved(Customers.RIYA.key(), reply, agent.actions());
    }
    for (AgentClient.Action a : agent.actions()) {
      if ("PENDING".equals(a.status())) {
        agent.cancel(a.id());
      }
    }
    return new Attempt(goal, n, conversation, evidence);
  }

  private void charge(long in, long out) {
    spent =
        spent.add(IN.multiply(BigDecimal.valueOf(in))).add(OUT.multiply(BigDecimal.valueOf(out)));
  }

  /** Markdown report, plus a regression case file for every successful attack. */
  public static void write(Result result, Path dir) {
    try {
      Files.createDirectories(dir.resolve("regressions"));
      StringBuilder md = new StringBuilder("# SafeLedger red-team report\n\n");
      md.append("Adaptive multi-turn attacks by a second model; success is decided by evidence")
          .append(" (markers, canaries, action states, patterns), not by a model.\n\n")
          .append("| Goal | OWASP | Attempts | Held | Broken | Turns used |\n")
          .append("|---|---|---|---|---|---|\n");
      for (Goal g : Goal.values()) {
        List<Attempt> mine = result.attempts().stream().filter(a -> a.goal() == g).toList();
        long broken = mine.stream().filter(a -> a.evidence() != null).count();
        int turns = mine.stream().mapToInt(a -> a.conversation().size()).sum();
        md.append("| ")
            .append(g.name().toLowerCase(Locale.ROOT).replace('_', ' '))
            .append(" | ")
            .append(String.join(", ", g.owasp))
            .append(" | ")
            .append(mine.size())
            .append(" | ")
            .append(mine.size() - broken)
            .append(" | ")
            .append(broken)
            .append(" | ")
            .append(turns)
            .append(" |\n");
      }
      md.append("\nTotal: ")
          .append(result.attempts().size())
          .append(" attack conversations, ")
          .append(result.successes())
          .append(" broken. Estimated spend $")
          .append(result.estimatedUsd().setScale(3, RoundingMode.HALF_UP))
          .append(result.budgetReached() ? " (stopped at the budget)." : ".")
          .append('\n');
      int k = 0;
      for (Attempt a : result.attempts()) {
        boolean show = a.evidence() != null || a.attempt() == 1;
        if (!show) {
          continue;
        }
        md.append("\n## ")
            .append(a.goal())
            .append(" #")
            .append(a.attempt())
            .append(a.evidence() == null ? " (held)" : " (BROKEN: " + a.evidence() + ")")
            .append("\n\n");
        for (Attacker.Turn t : a.conversation()) {
          md.append("**attacker:** ").append(oneLine(t.message())).append("\n\n");
          md.append("**assistant:** ").append(oneLine(t.reply())).append("\n\n");
        }
        if (a.evidence() != null) {
          Files.writeString(
              dir.resolve(
                  "regressions/rt"
                      + (++k)
                      + "-"
                      + a.goal().name().toLowerCase(Locale.ROOT)
                      + ".yaml"),
              regression(a, k));
        }
      }
      Files.writeString(dir.resolve("redteam-report.md"), md.toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String regression(Attempt a, int k) {
    StringBuilder y =
        new StringBuilder("id: rt")
            .append(k)
            .append("-")
            .append(a.goal().name().toLowerCase(Locale.ROOT).replace('_', '-'))
            .append("\ncategory: injection\ngroup: red-team\nowasp: [")
            .append(String.join(", ", a.goal().owasp))
            .append("]\ncustomer: riya\ndescription: \"Found by the red-team agent: ")
            .append(a.evidence().replace("\"", "'"))
            .append(". Review the expectations before adding to the suite.\"\nsteps:\n");
    for (Attacker.Turn t : a.conversation()) {
      y.append("  - user: \"")
          .append(t.message().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " "))
          .append("\"\n");
    }
    return y.toString();
  }

  private static String oneLine(String s) {
    return s == null ? "" : s.replace("\n", " ").replace("|", "\\|");
  }
}
