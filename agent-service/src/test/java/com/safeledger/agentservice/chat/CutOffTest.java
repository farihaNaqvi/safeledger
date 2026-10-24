package com.safeledger.agentservice.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class CutOffTest {

  private static ChatResponse finished(String reason) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder().content("Here are your").build(),
                ChatGenerationMetadata.builder().finishReason(reason).build())));
  }

  @Test
  void maxTokensAndLengthAreCutOff() {
    assertThat(AgentService.cutOff(finished("max_tokens"))).isTrue();
    assertThat(AgentService.cutOff(finished("MAX_TOKENS"))).isTrue();
    assertThat(AgentService.cutOff(finished("length"))).isTrue();
  }

  @Test
  void normalEndsAreNot() {
    assertThat(AgentService.cutOff(finished("end_turn"))).isFalse();
    assertThat(AgentService.cutOff(finished("stop"))).isFalse();
    assertThat(AgentService.cutOff(finished(null))).isFalse();
  }
}
