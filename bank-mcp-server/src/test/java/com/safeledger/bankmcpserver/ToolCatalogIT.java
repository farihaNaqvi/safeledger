package com.safeledger.bankmcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The tool list is the model's whole surface. CLAUDE.md hard rules 2 and 3: no tool may confirm or
 * execute a pending action, and no tool schema may accept a customer ID.
 */
class ToolCatalogIT extends McpIntegrationTestBase {

  private static final List<String> READ_TOOLS =
      List.of(
          "list_accounts",
          "get_balance",
          "list_cards",
          "search_transactions",
          "list_payees",
          "list_disputes");
  private static final List<String> WRITE_TOOLS =
      List.of(
          "request_card_freeze", "request_card_unfreeze", "request_dispute", "request_transfer");

  @Test
  void exposesExactlyTheTenSpecTools() {
    List<McpSchema.Tool> tools = connect(newCustomer(), newSession()).listTools().tools();

    assertThat(tools)
        .extracting(McpSchema.Tool::name)
        .containsExactlyInAnyOrderElementsOf(
            java.util.stream.Stream.concat(READ_TOOLS.stream(), WRITE_TOOLS.stream()).toList());
  }

  @Test
  void noToolCanConfirmOrExecute() {
    List<McpSchema.Tool> tools = connect(newCustomer(), newSession()).listTools().tools();

    assertThat(tools)
        .extracting(McpSchema.Tool::name)
        .noneMatch(n -> n.matches("(?i).*(confirm|execute|approve|cancel|complete).*"));
  }

  @Test
  void noToolSchemaAcceptsACustomerId() {
    List<McpSchema.Tool> tools = connect(newCustomer(), newSession()).listTools().tools();

    for (McpSchema.Tool tool : tools) {
      Map<String, Object> schema = tool.inputSchema();
      @SuppressWarnings("unchecked")
      Map<String, Object> properties =
          (Map<String, Object>) schema.getOrDefault("properties", Map.of());
      assertThat(properties.keySet())
          .as("parameters of %s", tool.name())
          .noneMatch(p -> p.toLowerCase().contains("customer") || p.toLowerCase().contains("user"));
      assertThat(String.valueOf(schema))
          .as("schema of %s", tool.name())
          .doesNotContainIgnoringCase("customerId");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void noToolTakesFreeTextForThePayee() {
    List<McpSchema.Tool> tools = connect(newCustomer(), newSession()).listTools().tools();

    // The transfer note reaches someone outside the bank, so only the customer writes it.
    assertThat(tools)
        .filteredOn(t -> t.name().equals("request_transfer"))
        .singleElement()
        .satisfies(
            t ->
                assertThat(((Map<String, Object>) t.inputSchema().get("properties")).keySet())
                    .containsExactlyInAnyOrder("fromAccountId", "payeeId", "amount"));
  }

  @Test
  void writeToolsDescribeTheConfirmationStepAndReadToolsAreReadOnly() {
    List<McpSchema.Tool> tools = connect(newCustomer(), newSession()).listTools().tools();

    for (McpSchema.Tool tool : tools) {
      if (WRITE_TOOLS.contains(tool.name())) {
        assertThat(tool.description()).contains("customer must confirm in the app");
      } else {
        assertThat(tool.annotations().readOnlyHint()).as(tool.name()).isTrue();
      }
    }
    assertThat(tools)
        .filteredOn(t -> t.name().equals("search_transactions"))
        .singleElement()
        .satisfies(t -> assertThat(t.description()).contains("untrusted_text"));
  }
}
