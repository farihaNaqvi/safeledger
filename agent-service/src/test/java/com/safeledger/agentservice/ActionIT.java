package com.safeledger.agentservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * GET /api/actions and the Confirm / Cancel buttons: forwarded to the MCP server's internal
 * endpoints with the session's signed identity, outside the model.
 */
class ActionIT extends AgentIntegrationTestBase {

  private String createFreeze(Browser browser) {
    model.thenCall("request_card_freeze", "{\"cardLast4\":\"4821\"}").thenReply("Please confirm.");
    return browser.chat("Freeze my card ending 4821").json("$.pendingActions[0].id");
  }

  @Test
  void confirmExecutesOnceAndTheNextTurnKnowsTheOutcome() {
    Browser browser = loggedIn(RIYA);
    String id = createFreeze(browser);

    Response first = browser.post("/api/actions/" + id + "/confirm", null);
    Response second = browser.post("/api/actions/" + id + "/confirm", null);

    assertThat(first.status()).isEqualTo(200);
    assertThat(first.<String>json("$.status")).isEqualTo("EXECUTED");
    assertThat(second.status()).isEqualTo(409);
    assertThat(second.<String>json("$.code")).isEqualTo("ACTION_NOT_PENDING");
    assertThat(mcp.executions).hasValue(1);

    model.thenReply("Your card is frozen.");
    browser.chat("Is it done?");
    assertThat(model.prompts.getLast().getContents())
        .contains("[App] The customer pressed Confirm for: Freeze card ending 4821")
        .contains("Result: EXECUTED.");
  }

  @Test
  void cancelledActionCannotBeConfirmed() {
    Browser browser = loggedIn(RIYA);
    String id = createFreeze(browser);

    Response cancel = browser.post("/api/actions/" + id + "/cancel", null);
    Response confirm = browser.post("/api/actions/" + id + "/confirm", null);

    assertThat(cancel.status()).isEqualTo(200);
    assertThat(cancel.<String>json("$.status")).isEqualTo("CANCELLED");
    assertThat(confirm.status()).isEqualTo(409);
    assertThat(mcp.executions).hasValue(0);

    model.thenReply("ok");
    browser.chat("What happened?");
    assertThat(model.prompts.getLast().getContents())
        .contains("[App] The customer pressed Cancel for: Freeze card ending 4821");
  }

  @Test
  void listShowsTheSessionsActionsWithStatus() {
    Browser browser = loggedIn(RIYA);
    String id = createFreeze(browser);

    Response r = browser.get("/api/actions");

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.<List<String>>json("$[*].id")).containsExactly(id);
    assertThat(r.<String>json("$[0].status")).isEqualTo("PENDING");
    assertThat(r.<String>json("$[0].summary")).isEqualTo("Freeze card ending 4821");
  }

  @Test
  void theNoteTheCustomerTypesIsSentWithConfirm() {
    Browser browser = loggedIn(RIYA);
    String freeze = createFreeze(browser);
    FakeMcpServer.Caller owner = mcp.actions.get(UUID.fromString(freeze)).owner();
    UUID transfer =
        mcp.createPending(owner, "request_transfer", "Transfer INR 500.00 to \"Sunview\".").id();

    Response r =
        browser.post("/api/actions/" + transfer + "/confirm", "{\"payeeNote\":\"October rent\"}");
    Response plain = browser.post("/api/actions/" + freeze + "/confirm", null);

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.<String>json("$.payeeNote")).isEqualTo("October rent");
    assertThat(mcp.actions.get(transfer).payeeNote()).isEqualTo("October rent");
    assertThat(plain.status()).isEqualTo(200);
    assertThat(mcp.actions.get(UUID.fromString(freeze)).payeeNote()).isNull();
  }

  @Test
  void anotherCustomerCannotSeeConfirmOrCancelTheAction() {
    Browser riya = loggedIn(RIYA);
    String id = createFreeze(riya);
    Browser kabir = loggedIn(KABIR);
    mcp.callers.clear();

    Response list = kabir.get("/api/actions");
    Response confirm = kabir.post("/api/actions/" + id + "/confirm", null);
    Response cancel = kabir.post("/api/actions/" + id + "/cancel", null);

    assertThat(list.<List<?>>json("$")).isEmpty();
    assertThat(confirm.status()).isEqualTo(404);
    assertThat(cancel.status()).isEqualTo(404);
    assertThat(mcp.executions).hasValue(0);
    assertThat(mcp.actions.get(UUID.fromString(id)).status()).isEqualTo("PENDING");
    assertThat(mcp.callers).hasSize(3).allMatch(customer(KABIR));
  }

  @Test
  void unknownOrMalformedActionIds() {
    Browser browser = loggedIn(RIYA);

    assertThat(browser.post("/api/actions/" + UUID.randomUUID() + "/confirm", null).status())
        .isEqualTo(404);
    assertThat(browser.post("/api/actions/not-a-uuid/confirm", null).status()).isEqualTo(400);
    assertThat(mcp.executions).hasValue(0);
  }

  @Test
  void confirmNeedsTheCsrfHeader() {
    Browser browser = loggedIn(RIYA);
    String id = createFreeze(browser);

    Response r = browser.postWithoutCsrf("/api/actions/" + id + "/confirm", "{}");

    assertThat(r.status()).isEqualTo(403);
    assertThat(mcp.executions).hasValue(0);
  }
}
