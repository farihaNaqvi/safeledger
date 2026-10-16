package com.safeledger.agentservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Demo login, the session cookie, and the cross-cutting browser checks. */
class SessionIT extends AgentIntegrationTestBase {

  @Test
  void listsTheSyntheticDemoCustomers() {
    Response r = new Browser().get("/api/customers");

    assertThat(r.status()).isEqualTo(200);
    List<String> ids = r.json("$[*].id");
    assertThat(ids).hasSize(3).contains(RIYA.toString(), KABIR.toString());
  }

  @Test
  void loginSetsAHttpOnlyStrictCookieAndReturnsTheCustomer() {
    Browser browser = new Browser();
    Response login = browser.login(RIYA);

    assertThat(login.status()).isEqualTo(200);
    assertThat(login.<String>json("$.customerId")).isEqualTo(RIYA.toString());
    assertThat(login.<String>json("$.customerName")).isEqualTo("Riya Sample");
    String cookie = login.raw().headers().firstValue("Set-Cookie").orElseThrow();
    assertThat(cookie).startsWith("SAFELEDGER_SESSION=").containsIgnoringCase("HttpOnly");
    assertThat(cookie).containsIgnoringCase("SameSite=Strict");

    Response current = browser.get("/api/session");
    assertThat(current.status()).isEqualTo(200);
    assertThat(current.<String>json("$.customerId")).isEqualTo(RIYA.toString());
  }

  @Test
  void unknownCustomerCannotLogIn() {
    Response r = new Browser().login(UUID.randomUUID());

    assertThat(r.status()).isEqualTo(400);
    assertThat(r.<String>json("$.code")).isEqualTo("UNKNOWN_CUSTOMER");
  }

  @Test
  void malformedLoginIsRejected() {
    Response r = new Browser().post("/api/session", "{\"customerId\":\"not-a-uuid\"}");

    assertThat(r.status()).isEqualTo(400);
    assertThat(r.<String>json("$.code")).isEqualTo("INVALID_REQUEST");
  }

  @Test
  void everyCustomerEndpointNeedsALogin() {
    Browser anonymous = new Browser();

    assertThat(anonymous.get("/api/session").status()).isEqualTo(401);
    assertThat(anonymous.get("/api/actions").status()).isEqualTo(401);
    assertThat(anonymous.chat("What is my balance?").status()).isEqualTo(401);
    assertThat(anonymous.post("/api/actions/" + UUID.randomUUID() + "/confirm", null).status())
        .isEqualTo(401);
    assertThat(anonymous.post("/api/actions/" + UUID.randomUUID() + "/cancel", null).status())
        .isEqualTo(401);
    assertThat(model.prompts).isEmpty();
    assertThat(mcp.callers).isEmpty();
  }

  @Test
  void logoutEndsTheSession() {
    Browser browser = loggedIn(RIYA);

    assertThat(browser.delete("/api/session").status()).isEqualTo(204);
    assertThat(browser.get("/api/session").status()).isEqualTo(401);
  }

  @Test
  void loggingInAgainStartsAFreshSession() {
    Browser browser = loggedIn(RIYA);
    model.thenCall("request_card_freeze", "{\"cardLast4\":\"4821\"}").thenReply("Please confirm.");
    browser.chat("Freeze my card ending 4821");

    browser.login(KABIR);

    Response actions = browser.get("/api/actions");
    assertThat(actions.status()).isEqualTo(200);
    assertThat(actions.<List<?>>json("$")).isEmpty();
  }

  @Test
  void stateChangingRequestsNeedTheCsrfHeader() {
    Browser browser = loggedIn(RIYA);

    Response r = browser.postWithoutCsrf("/api/chat", "{\"message\":\"hello\"}");

    assertThat(r.status()).isEqualTo(403);
    assertThat(r.<String>json("$.code")).isEqualTo("MISSING_CSRF_HEADER");
    assertThat(model.prompts).isEmpty();
    Response login =
        new Browser().postWithoutCsrf("/api/session", "{\"customerId\":\"" + RIYA + "\"}");
    assertThat(login.status()).isEqualTo(403);
  }

  @Test
  void correlationIdIsEchoedOrGenerated() {
    Browser browser = new Browser();

    Response given =
        browser.send(browser.request("/api/customers").header("X-Correlation-Id", "abc-123").GET());
    Response generated = browser.get("/api/customers");
    Response unsafe =
        browser.send(
            browser
                .request("/api/customers")
                .header("X-Correlation-Id", "<script>x</script>")
                .GET());

    assertThat(given.raw().headers().firstValue("X-Correlation-Id")).hasValue("abc-123");
    assertThat(generated.raw().headers().firstValue("X-Correlation-Id")).isPresent();
    assertThat(unsafe.raw().headers().firstValue("X-Correlation-Id").orElseThrow())
        .matches("[0-9a-f-]{36}");
  }
}
