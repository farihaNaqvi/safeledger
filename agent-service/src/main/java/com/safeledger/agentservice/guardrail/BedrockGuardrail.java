package com.safeledger.agentservice.guardrail;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ApplyGuardrailRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ApplyGuardrailResponse;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailAction;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentSource;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailOutputContent;

/**
 * Amazon Bedrock Guardrails through the standalone ApplyGuardrail API, so the same guardrail covers
 * input and output whichever chat model is in use. The guardrail itself (prompt attack filter,
 * denied topics, PII masking for card and account numbers) is defined in AWS; see the README
 * section "Running on Amazon Bedrock".
 */
public class BedrockGuardrail implements Guardrail {

  private static final Logger log = LoggerFactory.getLogger(BedrockGuardrail.class);

  static final String FALLBACK_BLOCKED =
      "Sorry, I can't help with that. I can help with your own accounts, cards, transactions,"
          + " payees and disputes.";

  private final BedrockRuntimeClient client;
  private final String guardrailId;
  private final String guardrailVersion;

  public BedrockGuardrail(
      BedrockRuntimeClient client, String guardrailId, String guardrailVersion) {
    this.client = client;
    this.guardrailId = guardrailId;
    this.guardrailVersion = guardrailVersion;
  }

  @Override
  public Result check(Source source, String text) {
    ApplyGuardrailResponse response =
        client.applyGuardrail(
            ApplyGuardrailRequest.builder()
                .guardrailIdentifier(guardrailId)
                .guardrailVersion(guardrailVersion)
                .source(
                    source == Source.INPUT
                        ? GuardrailContentSource.INPUT
                        : GuardrailContentSource.OUTPUT)
                .content(GuardrailContentBlock.fromText(t -> t.text(text)))
                .build());
    if (response.action() != GuardrailAction.GUARDRAIL_INTERVENED) {
      return Result.passed(text);
    }
    log.warn("Security event: guardrail intervened source={}", source);
    List<GuardrailOutputContent> outputs = response.outputs();
    String replacement =
        outputs == null || outputs.isEmpty() || outputs.getFirst().text() == null
            ? FALLBACK_BLOCKED
            : outputs.getFirst().text();
    return new Result(true, replacement);
  }

  @Override
  public String name() {
    return "bedrock";
  }
}
