package com.safeledger.eval.run;

import com.safeledger.eval.cases.Customers;
import com.safeledger.eval.cases.EvalCase;
import com.safeledger.eval.report.CaseResult;
import com.safeledger.eval.score.Scorer;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Plays each case against the running stack in a fresh session, one at a time, scores it, and
 * cancels whatever it left pending (the per-customer pending limit spans sessions).
 */
public final class LiveRunner {

  private final String agentUrl;
  private final Duration turnTimeout;
  private final AuditReader audit;
  private volatile String promptVersion = "unknown";

  public LiveRunner(String agentUrl, Duration turnTimeout, AuditReader audit) {
    this.agentUrl = agentUrl;
    this.turnTimeout = turnTimeout;
    this.audit = audit;
  }

  public CaseResult run(EvalCase evalCase) {
    UUID customerId = Customers.byKey(evalCase.customer()).id();
    AgentClient client = new AgentClient(agentUrl, turnTimeout);
    List<Scorer.StepObservation> observations = new ArrayList<>();
    List<CaseResult.Transcript> transcript = new ArrayList<>();
    Set<String> confirmed = new HashSet<>();
    long inputTokens = 0;
    long outputTokens = 0;
    String lastPendingId = null;
    try {
      client.login(customerId);
      for (EvalCase.Step step : evalCase.steps()) {
        if (step.isMessage()) {
          Timestamp from = audit.now();
          AgentClient.ChatResult chat = client.chat(step.user());
          List<String> tools = audit.toolCalls(customerId, from, audit.now());
          if (chat.promptVersion() != null) {
            promptVersion = chat.promptVersion();
          }
          inputTokens += chat.inputTokens();
          outputTokens += chat.outputTokens();
          if (!chat.pending().isEmpty()) {
            lastPendingId = chat.pending().getLast().id();
          }
          observations.add(
              new Scorer.StepObservation(
                  step,
                  chat.status(),
                  chat.reply(),
                  tools,
                  chat.pending().stream().map(AgentClient.Pending::summary).toList(),
                  chat.guardrailIntervened(),
                  null));
          transcript.add(new CaseResult.Transcript("customer", step.user(), List.of()));
          transcript.add(new CaseResult.Transcript("assistant", chat.reply(), tools));
        } else {
          AgentClient.ActionResult result =
              lastPendingId == null
                  ? new AgentClient.ActionResult(404, null)
                  : "confirm".equals(step.action())
                      ? client.confirm(lastPendingId)
                      : client.cancel(lastPendingId);
          if ("confirm".equals(step.action()) && result.status() == 200) {
            confirmed.add(lastPendingId);
          }
          observations.add(
              new Scorer.StepObservation(
                  step, result.status(), null, List.of(), List.of(), false, result.actionStatus()));
          transcript.add(
              new CaseResult.Transcript(
                  "app",
                  "Customer pressed " + step.action() + " -> " + result.actionStatus(),
                  List.of()));
          lastPendingId = null;
        }
      }
      List<AgentClient.Action> actions = client.actions();
      Set<String> executed =
          actions.stream()
              .filter(a -> "EXECUTED".equals(a.status()) || "FAILED".equals(a.status()))
              .map(AgentClient.Action::id)
              .collect(Collectors.toSet());
      List<Scorer.Failure> failures = Scorer.score(evalCase, observations, executed, confirmed);
      cancelPending(client, actions);
      return new CaseResult(evalCase, failures, transcript, inputTokens, outputTokens);
    } catch (RuntimeException e) {
      return new CaseResult(
          evalCase,
          List.of(new Scorer.Failure(0, Scorer.ERROR, e.toString())),
          transcript,
          inputTokens,
          outputTokens);
    }
  }

  /** The system prompt version agent-service reported, once a turn has run. */
  public String promptVersion() {
    return promptVersion;
  }

  private static void cancelPending(AgentClient client, List<AgentClient.Action> actions) {
    actions.stream().filter(a -> "PENDING".equals(a.status())).forEach(a -> client.cancel(a.id()));
  }
}
