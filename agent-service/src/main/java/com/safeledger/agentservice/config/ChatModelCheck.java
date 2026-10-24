package com.safeledger.agentservice.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fails startup when Bedrock is selected without a model ID, instead of letting the provider fall
 * back to a model nobody chose.
 */
@Component
public class ChatModelCheck {

  static final String PROVIDER = "spring.ai.model.chat";
  static final String BEDROCK_MODEL = "spring.ai.bedrock.converse.chat.model";

  public ChatModelCheck(Environment environment) {
    if ("bedrock-converse".equals(environment.getProperty(PROVIDER))
        && !StringUtils.hasText(environment.getProperty(BEDROCK_MODEL))) {
      throw new IllegalStateException(
          "Bedrock is selected but BEDROCK_MODEL_ID is not set. Set it to a model your account can"
              + " use in AWS_REGION, or set SAFELEDGER_CHAT_MODEL=ollama for local development.");
    }
  }
}
