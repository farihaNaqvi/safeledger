package com.safeledger.agentservice.guardrail;

import com.safeledger.agentservice.config.AgentProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Bedrock Guardrails when {@code safeledger.guardrail.enabled=true}; otherwise a pass-through, so
 * the service also runs on a local model without AWS. Every reply reports which one was active.
 */
@Configuration(proxyBeanMethods = false)
public class GuardrailConfig {

  @Bean
  @ConditionalOnProperty(name = "safeledger.guardrail.enabled", havingValue = "true")
  Guardrail bedrockGuardrail(AgentProperties properties) {
    AgentProperties.Guardrail config = properties.guardrail();
    if (config.id() == null || config.id().isBlank()) {
      throw new IllegalStateException(
          "safeledger.guardrail.enabled=true needs BEDROCK_GUARDRAIL_ID");
    }
    BedrockRuntimeClient client =
        BedrockRuntimeClient.builder()
            .region(Region.of(config.region()))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .build();
    return new BedrockGuardrail(client, config.id(), config.version());
  }

  @Bean
  @ConditionalOnMissingBean
  Guardrail noGuardrail() {
    return new Guardrail() {
      @Override
      public Result check(Source source, String text) {
        return Result.passed(text);
      }

      @Override
      public String name() {
        return "none";
      }
    };
  }
}
