package com.safeledger.agentservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** A turn that runs past safeledger.agent.turn-timeout is cut off with a polite reply. */
@TestPropertySource(properties = "safeledger.agent.turn-timeout=1s")
class TurnTimeoutIT extends AgentIntegrationTestBase {

  @Test
  void slowTurnIsCutOff() {
    Browser browser = loggedIn(RIYA);
    model.then(
        p -> {
          try {
            Thread.sleep(5_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return ScriptedChatModel.reply("too late");
        });

    Instant start = Instant.now();
    Response r = browser.chat("What is my balance?");

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.<String>json("$.reply")).contains("took too long");
    assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(4));
  }
}
