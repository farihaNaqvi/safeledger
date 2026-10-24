package com.safeledger.agentservice.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailAssessment;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentFilter;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentPolicyAssessment;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailPiiEntityFilter;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailSensitiveInformationPolicyAssessment;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailTopic;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailTopicPolicyAssessment;

class BedrockGuardrailTest {

  private static GuardrailAssessment pii(String action) {
    return GuardrailAssessment.builder()
        .sensitiveInformationPolicy(
            GuardrailSensitiveInformationPolicyAssessment.builder()
                .piiEntities(
                    GuardrailPiiEntityFilter.builder()
                        .type("CREDIT_DEBIT_CARD_NUMBER")
                        .action(action)
                        .detected(true)
                        .build())
                .build())
        .build();
  }

  @Test
  void maskingOnlyIsNotABlock() {
    assertThat(BedrockGuardrail.blocked(List.of(pii("ANONYMIZED")))).isFalse();
  }

  @Test
  void blockedPiiTopicOrPromptAttackIsABlock() {
    GuardrailAssessment topic =
        GuardrailAssessment.builder()
            .topicPolicy(
                GuardrailTopicPolicyAssessment.builder()
                    .topics(
                        GuardrailTopic.builder()
                            .name("Investment advice")
                            .action("BLOCKED")
                            .build())
                    .build())
            .build();
    GuardrailAssessment attack =
        GuardrailAssessment.builder()
            .contentPolicy(
                GuardrailContentPolicyAssessment.builder()
                    .filters(
                        GuardrailContentFilter.builder()
                            .type("PROMPT_ATTACK")
                            .action("BLOCKED")
                            .build())
                    .build())
            .build();

    assertThat(BedrockGuardrail.blocked(List.of(pii("BLOCKED")))).isTrue();
    assertThat(BedrockGuardrail.blocked(List.of(topic))).isTrue();
    assertThat(BedrockGuardrail.blocked(List.of(pii("ANONYMIZED"), attack))).isTrue();
  }

  @Test
  void anInterventionWithoutDetailsCountsAsABlock() {
    assertThat(BedrockGuardrail.blocked(List.of())).isTrue();
    assertThat(BedrockGuardrail.blocked(null)).isTrue();
  }
}
