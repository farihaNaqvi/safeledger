package com.safeledger.agentservice.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ChatModelCheckTest {

  @Test
  void bedrockWithoutAModelIdFails() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty(ChatModelCheck.PROVIDER, "bedrock-converse")
            .withProperty(ChatModelCheck.BEDROCK_MODEL, "");

    assertThatThrownBy(() -> new ChatModelCheck(env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("BEDROCK_MODEL_ID");
  }

  @Test
  void bedrockWithAModelIdStarts() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty(ChatModelCheck.PROVIDER, "bedrock-converse")
            .withProperty(ChatModelCheck.BEDROCK_MODEL, "example.model-id-v1:0");

    assertThatCode(() -> new ChatModelCheck(env)).doesNotThrowAnyException();
  }

  @Test
  void ollamaDoesNotNeedABedrockModelId() {
    MockEnvironment env = new MockEnvironment().withProperty(ChatModelCheck.PROVIDER, "ollama");

    assertThatCode(() -> new ChatModelCheck(env)).doesNotThrowAnyException();
  }
}
