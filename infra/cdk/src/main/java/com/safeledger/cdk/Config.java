package com.safeledger.cdk;

import java.nio.file.Path;
import software.constructs.Node;

/**
 * Deploy-time settings, from CDK context ({@code -c key=value} or cdk.json).
 *
 * @param allowedCidr who may reach the load balancer, e.g. your IP as {@code 203.0.113.7/32}
 * @param chatModel {@code anthropic} (Claude API, key in Secrets Manager) or {@code
 *     bedrock-converse}
 * @param bedrockModelId model or inference profile for {@code bedrock-converse}
 * @param anthropicSecretName Secrets Manager secret holding the Claude API key, created beforehand
 * @param alarmEmail optional: where CloudWatch alarms are emailed (confirm the subscription)
 * @param imagesFromRegistry tests only: placeholder images instead of building the services
 * @param repoRoot the repository root, the Docker build context
 */
public record Config(
    String allowedCidr,
    String chatModel,
    String bedrockModelId,
    String anthropicSecretName,
    String alarmEmail,
    boolean imagesFromRegistry,
    Path repoRoot) {

  public static Config from(Node node) {
    String cidr = text(node, "allowedCidr", null);
    if (cidr == null || !cidr.matches("\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}")) {
      throw new IllegalArgumentException(
          "Pass the CIDR allowed to reach the demo, e.g. -c allowedCidr=203.0.113.7/32");
    }
    String model = text(node, "chatModel", "anthropic");
    if (!model.equals("anthropic") && !model.equals("bedrock-converse")) {
      throw new IllegalArgumentException("chatModel must be anthropic or bedrock-converse");
    }
    String bedrockModel = text(node, "bedrockModelId", "");
    if (model.equals("bedrock-converse") && bedrockModel.isBlank()) {
      throw new IllegalArgumentException("chatModel=bedrock-converse needs -c bedrockModelId=...");
    }
    return new Config(
        cidr,
        model,
        bedrockModel,
        text(node, "anthropicSecretName", "safeledger/anthropic-api-key"),
        text(node, "alarmEmail", null),
        Boolean.parseBoolean(text(node, "imagesFromRegistry", "false")),
        Path.of(text(node, "repoRoot", "../..")).toAbsolutePath().normalize());
  }

  boolean usesClaudeApi() {
    return chatModel.equals("anthropic");
  }

  private static String text(Node node, String key, String fallback) {
    Object value = node.tryGetContext(key);
    return value == null ? fallback : value.toString();
  }
}
