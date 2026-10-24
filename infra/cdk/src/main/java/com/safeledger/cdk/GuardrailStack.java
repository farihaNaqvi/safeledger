package com.safeledger.cdk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.bedrock.CfnGuardrail;
import software.amazon.awscdk.services.bedrock.CfnGuardrailVersion;
import software.constructs.Construct;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Bedrock guardrail, built from {@code infra/bedrock/guardrail.json} so local development and
 * the deployed stack use the same definition. Named with a "-cdk" suffix so it does not clash with
 * a guardrail created by hand from the same file.
 */
public class GuardrailStack extends Stack {

  final CfnGuardrail guardrail;
  final CfnGuardrailVersion version;

  public GuardrailStack(Construct scope, String id, StackProps props, Config config) {
    super(scope, id, props);
    JsonNode json = read(config.repoRoot().resolve("infra/bedrock/guardrail.json"));

    guardrail =
        CfnGuardrail.Builder.create(this, "Guardrail")
            .name(json.path("name").asString() + "-cdk")
            .description(json.path("description").asString())
            .blockedInputMessaging(json.path("blockedInputMessaging").asString())
            .blockedOutputsMessaging(json.path("blockedOutputsMessaging").asString())
            .contentPolicyConfig(
                CfnGuardrail.ContentPolicyConfigProperty.builder()
                    .filtersConfig(contentFilters(json.path("contentPolicyConfig")))
                    .build())
            .topicPolicyConfig(
                CfnGuardrail.TopicPolicyConfigProperty.builder()
                    .topicsConfig(topics(json.path("topicPolicyConfig")))
                    .build())
            .sensitiveInformationPolicyConfig(
                CfnGuardrail.SensitiveInformationPolicyConfigProperty.builder()
                    .piiEntitiesConfig(piiEntities(json.path("sensitiveInformationPolicyConfig")))
                    .regexesConfig(regexes(json.path("sensitiveInformationPolicyConfig")))
                    .build())
            .build();

    version =
        CfnGuardrailVersion.Builder.create(this, "Version")
            .guardrailIdentifier(guardrail.getAttrGuardrailId())
            .description("Deployed by CDK from infra/bedrock/guardrail.json")
            .build();
  }

  static JsonNode read(Path file) {
    try {
      return JsonMapper.builder().build().readTree(Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
  }

  private static List<Object> contentFilters(JsonNode policy) {
    List<Object> out = new ArrayList<>();
    for (JsonNode f : policy.path("filtersConfig")) {
      var b =
          CfnGuardrail.ContentFilterConfigProperty.builder()
              .type(f.path("type").asString())
              .inputStrength(f.path("inputStrength").asString())
              .outputStrength(f.path("outputStrength").asString());
      out.add(b.build());
    }
    return out;
  }

  private static List<Object> topics(JsonNode policy) {
    List<Object> out = new ArrayList<>();
    for (JsonNode t : policy.path("topicsConfig")) {
      List<String> examples = new ArrayList<>();
      t.path("examples").forEach(e -> examples.add(e.asString()));
      var b =
          CfnGuardrail.TopicConfigProperty.builder()
              .name(t.path("name").asString())
              .type(t.path("type").asString())
              .definition(t.path("definition").asString())
              .examples(examples);
      if (t.has("inputEnabled")) {
        b.inputEnabled(t.path("inputEnabled").asBoolean());
      }
      if (t.has("outputEnabled")) {
        b.outputEnabled(t.path("outputEnabled").asBoolean());
      }
      out.add(b.build());
    }
    return out;
  }

  private static List<Object> piiEntities(JsonNode policy) {
    List<Object> out = new ArrayList<>();
    for (JsonNode p : policy.path("piiEntitiesConfig")) {
      out.add(
          CfnGuardrail.PiiEntityConfigProperty.builder()
              .type(p.path("type").asString())
              .action(p.path("action").asString())
              .inputAction(p.path("inputAction").asString())
              .outputAction(p.path("outputAction").asString())
              .inputEnabled(p.path("inputEnabled").asBoolean())
              .outputEnabled(p.path("outputEnabled").asBoolean())
              .build());
    }
    return out;
  }

  private static List<Object> regexes(JsonNode policy) {
    List<Object> out = new ArrayList<>();
    for (JsonNode r : policy.path("regexesConfig")) {
      out.add(
          CfnGuardrail.RegexConfigProperty.builder()
              .name(r.path("name").asString())
              .description(r.path("description").asString())
              .pattern(r.path("pattern").asString())
              .action(r.path("action").asString())
              .inputAction(r.path("inputAction").asString())
              .outputAction(r.path("outputAction").asString())
              .inputEnabled(r.path("inputEnabled").asBoolean())
              .outputEnabled(r.path("outputEnabled").asBoolean())
              .build());
    }
    return out;
  }
}
