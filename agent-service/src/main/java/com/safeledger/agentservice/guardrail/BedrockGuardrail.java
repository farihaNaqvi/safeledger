package com.safeledger.agentservice.guardrail;

import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ApplyGuardrailRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ApplyGuardrailResponse;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailAction;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailAssessment;
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
    boolean blocked = blocked(response.assessments());
    log.warn("Security event: guardrail {} source={}", blocked ? "blocked" : "masked", source);
    List<GuardrailOutputContent> outputs = response.outputs();
    String replacement =
        outputs == null || outputs.isEmpty() || outputs.getFirst().text() == null
            ? FALLBACK_BLOCKED
            : outputs.getFirst().text();
    return blocked ? Result.blocked(replacement) : Result.masked(replacement);
  }

  /** Anything other than masking (ANONYMIZED) means the text was blocked. */
  static boolean blocked(List<GuardrailAssessment> assessments) {
    if (assessments == null || assessments.isEmpty()) {
      return true;
    }
    return assessments.stream()
        .flatMap(
            a ->
                Stream.of(
                        a.topicPolicy() == null
                            ? Stream.<String>empty()
                            : a.topicPolicy().topics().stream().map(t -> t.actionAsString()),
                        a.contentPolicy() == null
                            ? Stream.<String>empty()
                            : a.contentPolicy().filters().stream().map(f -> f.actionAsString()),
                        a.wordPolicy() == null
                            ? Stream.<String>empty()
                            : Stream.concat(
                                a.wordPolicy().customWords().stream().map(w -> w.actionAsString()),
                                a.wordPolicy().managedWordLists().stream()
                                    .map(w -> w.actionAsString())),
                        a.sensitiveInformationPolicy() == null
                            ? Stream.<String>empty()
                            : Stream.concat(
                                a.sensitiveInformationPolicy().piiEntities().stream()
                                    .map(p -> p.actionAsString()),
                                a.sensitiveInformationPolicy().regexes().stream()
                                    .map(r -> r.actionAsString())))
                    .flatMap(s -> s))
        .anyMatch("BLOCKED"::equals);
  }

  @Override
  public String name() {
    return "bedrock";
  }
}
