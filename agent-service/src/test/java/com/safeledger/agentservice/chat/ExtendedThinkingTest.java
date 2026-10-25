package com.safeledger.agentservice.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class ExtendedThinkingTest {

  @Test
  void claudeRequestsHaveExtendedThinkingTurnedOff() {
    AnthropicChatOptions.Builder builder =
        AnthropicChatOptions.builder().model("claude-haiku-5-5").thinkingAdaptive();

    AgentService.withoutExtendedThinking(builder);

    AnthropicChatOptions options = builder.build();
    assertThat(options.getThinking().isDisabled()).isTrue();
    assertThat(options.getModel()).isEqualTo("claude-haiku-5-5");
  }

  @Test
  void otherProvidersAreLeftAlone() {
    ChatOptions.Builder<?> builder = ToolCallingChatOptions.builder().model("other");

    AgentService.withoutExtendedThinking(builder);

    assertThat(builder.build().getModel()).isEqualTo("other");
  }
}
