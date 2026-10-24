package com.safeledger.agentservice.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fails startup when the selected provider is missing its model ID or API key, instead of letting
 * it fall back to a model nobody chose or fail on the first chat.
 */
@Component
public class ChatModelCheck {

  static final String PROVIDER = "spring.ai.model.chat";
  static final String BEDROCK_MODEL = "spring.ai.bedrock.converse.chat.model";
  static final String ANTHROPIC_KEY = "spring.ai.anthropic.api-key";

  public ChatModelCheck(Environment environment) {
    String provider = environment.getProperty(PROVIDER);
    if ("bedrock-converse".equals(provider)
        && !StringUtils.hasText(environment.getProperty(BEDROCK_MODEL))) {
      throw new IllegalStateException(
          "Bedrock is selected but BEDROCK_MODEL_ID is not set. Set it to a model your account can"
              + " use in AWS_REGION, or set SAFELEDGER_CHAT_MODEL=ollama for local development.");
    }
    if ("anthropic".equals(provider)
        && !StringUtils.hasText(environment.getProperty(ANTHROPIC_KEY))) {
      throw new IllegalStateException(
          "The Claude API is selected but ANTHROPIC_API_KEY is not set.");
    }
  }
}
