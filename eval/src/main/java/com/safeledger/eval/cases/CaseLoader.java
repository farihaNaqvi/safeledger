package com.safeledger.eval.cases;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Reads every {@code *.yaml} case under a directory, sorted by file name, and validates it. */
public final class CaseLoader {

  /** The MCP server's tools (bank-mcp-server Phase 3). */
  public static final Set<String> READ_TOOLS =
      Set.of(
          "list_accounts",
          "get_balance",
          "list_cards",
          "search_transactions",
          "list_payees",
          "list_disputes");

  public static final Set<String> WRITE_TOOLS =
      Set.of("request_card_freeze", "request_card_unfreeze", "request_dispute", "request_transfer");

  private static final YAMLMapper YAML =
      YAMLMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private CaseLoader() {}

  public static List<EvalCase> load(Path directory) {
    List<Path> files;
    try (Stream<Path> paths = Files.list(directory)) {
      files = paths.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot list cases in " + directory, e);
    }
    List<EvalCase> cases = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Path file : files) {
      EvalCase evalCase;
      try {
        evalCase = YAML.readValue(file.toFile(), EvalCase.class);
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("Cannot read " + file.getFileName() + ": " + e, e);
      }
      List<String> problems = validate(evalCase);
      if (!ids.add(String.valueOf(evalCase.id()))) {
        problems = new ArrayList<>(problems);
        problems.add("duplicate id");
      }
      if (!problems.isEmpty()) {
        throw new IllegalArgumentException(file.getFileName() + ": " + String.join("; ", problems));
      }
      cases.add(evalCase);
    }
    return List.copyOf(cases);
  }

  static List<String> validate(EvalCase c) {
    List<String> problems = new ArrayList<>();
    if (c.id() == null || c.id().isBlank()) {
      problems.add("id is required");
    }
    if (!EvalCase.GOLDEN.equals(c.category()) && !EvalCase.INJECTION.equals(c.category())) {
      problems.add("category must be golden or injection");
    }
    if (c.group() == null || c.group().isBlank()) {
      problems.add("group is required");
    }
    try {
      Customers.byKey(c.customer());
    } catch (IllegalArgumentException e) {
      problems.add(e.getMessage());
    }
    if (EvalCase.INJECTION.equals(c.category()) && c.owasp().isEmpty()) {
      problems.add("injection cases need at least one owasp tag (LLM01..LLM10)");
    }
    for (String tag : c.owasp()) {
      if (!Owasp.CATEGORIES.containsKey(tag)) {
        problems.add("unknown owasp tag " + tag);
      }
    }
    if (c.steps().isEmpty() || !c.steps().getFirst().isMessage()) {
      problems.add("the first step must be a customer message");
    }
    for (int i = 0; i < c.steps().size(); i++) {
      EvalCase.Step step = c.steps().get(i);
      String where = "step " + (i + 1) + ": ";
      if ((step.user() == null) == (step.action() == null)) {
        problems.add(where + "exactly one of user or action");
      }
      if (step.action() != null) {
        if (!"confirm".equals(step.action()) && !"cancel".equals(step.action())) {
          problems.add(where + "action must be confirm or cancel");
        }
        if (EvalCase.INJECTION.equals(c.category()) && "confirm".equals(step.action())) {
          problems.add(where + "injection cases never confirm");
        }
      }
      if (step.expect() != null) {
        for (String tool : step.expect().tools()) {
          if (!READ_TOOLS.contains(tool) && !WRITE_TOOLS.contains(tool)) {
            problems.add(where + "unknown tool " + tool);
          }
        }
        if (step.expect().pending() != null && step.expect().pendingMax() != null) {
          problems.add(where + "use pending or pending_max, not both");
        }
        for (String tool : step.expect().writes()) {
          if (!WRITE_TOOLS.contains(tool)) {
            problems.add(where + tool + " is not a write tool");
          }
        }
      }
    }
    return problems;
  }
}
