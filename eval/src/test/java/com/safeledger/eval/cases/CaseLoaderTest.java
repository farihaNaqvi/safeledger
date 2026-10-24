package com.safeledger.eval.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaseLoaderTest {

  private static final Path CASES = Path.of("src/test/resources/cases");

  @Test
  void theSuiteHasTheSpecsCaseCounts() {
    List<EvalCase> cases = CaseLoader.load(CASES);

    assertThat(cases.stream().filter(c -> c.category().equals(EvalCase.GOLDEN))).hasSize(20);
    assertThat(cases.stream().filter(c -> c.category().equals(EvalCase.INJECTION))).hasSize(15);
  }

  @Test
  void injectionCasesCoverEveryAttackTheSpecLists() {
    List<String> groups =
        CaseLoader.load(CASES).stream()
            .filter(c -> c.category().equals(EvalCase.INJECTION))
            .map(EvalCase::group)
            .distinct()
            .toList();

    assertThat(groups)
        .contains("data-injection", "direct", "cross-customer", "limits", "transfer-note");
  }

  @Test
  void everySeededInjectionIsUsedAndEveryCustomerIsCovered() {
    List<EvalCase> cases = CaseLoader.load(CASES);

    assertThat(cases.stream().filter(c -> c.group().equals("data-injection"))).hasSize(6);
    assertThat(cases.stream().map(EvalCase::customer).distinct())
        .containsExactlyInAnyOrder("riya", "kabir", "meera");
  }

  @Test
  void caseIdsMatchTheirFileNames() throws IOException {
    try (var files = Files.list(CASES)) {
      files.forEach(
          f -> {
            EvalCase c =
                CaseLoader.load(CASES).stream()
                    .filter(x -> f.getFileName().toString().equals(x.id() + ".yaml"))
                    .findFirst()
                    .orElse(null);
            assertThat(c).as(f.getFileName().toString()).isNotNull();
          });
    }
  }

  @Test
  void injectionCasesMayNotConfirm(@TempDir Path dir) throws IOException {
    write(
        dir,
        "x.yaml",
        """
        id: x
        category: injection
        group: direct
        customer: riya
        steps:
          - user: hi
          - action: confirm
        """);

    assertThatThrownBy(() -> CaseLoader.load(dir)).hasMessageContaining("never confirm");
  }

  @Test
  void typosAndUnknownToolsAreRejected(@TempDir Path dir) throws IOException {
    write(
        dir,
        "a.yaml",
        """
        id: a
        category: golden
        group: read
        customer: riya
        steps:
          - user: hi
            expect:
              tool: [get_balance]
        """);
    assertThatThrownBy(() -> CaseLoader.load(dir)).hasMessageContaining("a.yaml");

    Files.delete(dir.resolve("a.yaml"));
    write(
        dir,
        "b.yaml",
        """
        id: b
        category: golden
        group: read
        customer: bob
        steps:
          - user: hi
            expect:
              tools: [confirm_action]
              writes: [get_balance]
        """);
    assertThatThrownBy(() -> CaseLoader.load(dir))
        .hasMessageContaining("Unknown customer")
        .hasMessageContaining("unknown tool confirm_action")
        .hasMessageContaining("get_balance is not a write tool");
  }

  @Test
  void duplicateIdsAreRejected(@TempDir Path dir) throws IOException {
    String body =
        """
        id: same
        category: golden
        group: read
        customer: riya
        steps:
          - user: hi
        """;
    write(dir, "1.yaml", body);
    write(dir, "2.yaml", body);

    assertThatThrownBy(() -> CaseLoader.load(dir)).hasMessageContaining("duplicate id");
  }

  private static void write(Path dir, String name, String body) throws IOException {
    Files.writeString(dir.resolve(name), body);
  }
}
