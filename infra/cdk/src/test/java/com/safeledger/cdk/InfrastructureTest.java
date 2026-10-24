package com.safeledger.cdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.assertions.Template;
import tools.jackson.databind.JsonNode;

/** The security and cost properties of the deployed stack, checked on the synthesized templates. */
class InfrastructureTest {

  static final String CIDR = "203.0.113.7/32";

  static SafeLedgerApp.Stacks stacks;

  @BeforeAll
  static void synth() {
    stacks = SafeLedgerApp.build(app(Map.of()));
  }

  static App app(Map<String, Object> extra) {
    Map<String, Object> context = new HashMap<>();
    context.put("allowedCidr", CIDR);
    context.put("imagesFromRegistry", "true");
    context.putAll(extra);
    return App.Builder.create().context(context).build();
  }

  @SuppressWarnings("unchecked")
  static Map<String, Map<String, Object>> resources(Stack stack, String type) {
    Map<String, Object> json = (Map<String, Object>) Template.fromStack(stack).toJSON();
    Map<String, Map<String, Object>> out = new HashMap<>();
    ((Map<String, Map<String, Object>>) json.get("Resources"))
        .forEach(
            (id, r) -> {
              if (type == null || type.equals(r.get("Type"))) {
                out.put(id, r);
              }
            });
    return out;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> props(Map<String, Object> resource) {
    return (Map<String, Object>) resource.get("Properties");
  }

  static List<Stack> all() {
    return List.of(
        stacks.network(),
        stacks.data(),
        stacks.guardrail(),
        stacks.services(),
        stacks.observability());
  }

  @Test
  void noNatGatewayAndInterfaceEndpointsInOneAz() {
    assertThat(resources(stacks.network(), "AWS::EC2::NatGateway")).isEmpty();
    Map<String, Map<String, Object>> endpoints =
        resources(stacks.network(), "AWS::EC2::VPCEndpoint");
    assertThat(endpoints).hasSize(6);
    endpoints.values().stream()
        .map(InfrastructureTest::props)
        .filter(p -> "Interface".equals(p.get("VpcEndpointType")))
        .forEach(p -> assertThat((List<?>) p.get("SubnetIds")).hasSize(1));
  }

  @Test
  @SuppressWarnings("unchecked")
  void theLoadBalancerOnlyAcceptsTheAllowedCidrAndHidesActuator() {
    List<Map<String, Object>> albIngress =
        resources(stacks.network(), "AWS::EC2::SecurityGroup").entrySet().stream()
            .filter(e -> e.getKey().startsWith("AlbSg"))
            .map(e -> (List<Map<String, Object>>) props(e.getValue()).get("SecurityGroupIngress"))
            .findFirst()
            .orElseThrow();
    assertThat(albIngress)
        .singleElement()
        .satisfies(
            rule -> {
              assertThat(rule.get("CidrIp")).isEqualTo(CIDR);
              assertThat(rule.get("FromPort")).isEqualTo(80);
            });
    assertThat(resources(stacks.services(), "AWS::ElasticLoadBalancingV2::ListenerRule").values())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(props(r).toString()).contains("/actuator/*").contains("fixed-response");
            });
  }

  @Test
  @SuppressWarnings("unchecked")
  void onlyAgentServiceGetsAPublicIpAndEveryServiceRollsBackOnFailure() {
    Map<String, Map<String, Object>> services = resources(stacks.services(), "AWS::ECS::Service");
    assertThat(services).hasSize(3);
    List<String> publicOnes = new ArrayList<>();
    services.forEach(
        (id, r) -> {
          Map<String, Object> p = props(r);
          Map<String, Object> vpc =
              (Map<String, Object>)
                  ((Map<String, Object>) p.get("NetworkConfiguration")).get("AwsvpcConfiguration");
          if ("ENABLED".equals(vpc.get("AssignPublicIp"))) {
            publicOnes.add(id);
          }
          assertThat(p.toString())
              .contains("DeploymentCircuitBreaker={Enable=true, Rollback=true}");
        });
    assertThat(publicOnes).singleElement().satisfies(id -> assertThat(id).startsWith("Agent"));
  }

  @Test
  void onlyAgentServiceMayCallBedrockAndOnlyForTheGuardrail() {
    List<String> bedrockPolicies = bedrockPolicies(stacks.services());
    assertThat(bedrockPolicies)
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p).contains("AgentTaskTaskRole").contains("bedrock:ApplyGuardrail");
              assertThat(p).doesNotContain("bedrock:InvokeModel").doesNotContain("bedrock:*");
            });
  }

  @Test
  void withABedrockModelTheAgentMayInvokeThatModelOnly() {
    SafeLedgerApp.Stacks bedrock =
        SafeLedgerApp.build(
            app(
                Map.of(
                    "chatModel", "bedrock-converse",
                    "bedrockModelId", "us.anthropic.claude-haiku-5-5")));

    assertThat(bedrockPolicies(bedrock.services()))
        .singleElement()
        .satisfies(
            p ->
                assertThat(p)
                    .contains("foundation-model/anthropic.claude-haiku-5-5")
                    .contains("inference-profile/us.anthropic.claude-haiku-5-5")
                    .doesNotContain("foundation-model/*"));
  }

  private static List<String> bedrockPolicies(Stack services) {
    return resources(services, "AWS::IAM::Policy").values().stream()
        .map(r -> props(r).toString())
        .filter(p -> p.contains("bedrock:"))
        .toList();
  }

  @Test
  void secretsComeFromSecretsManagerNotFromPlainEnvironment() {
    String tasks = resources(stacks.services(), "AWS::ECS::TaskDefinition").toString();
    for (String name :
        List.of(
            "IDENTITY_HMAC_SECRET",
            "ANTHROPIC_API_KEY",
            "BANK_DB_PASSWORD",
            "MCP_DB_PASSWORD",
            "BANK_DB_USERNAME")) {
      assertThat(tasks).contains("Name=" + name + ", ValueFrom=");
      assertThat(tasks).doesNotContain("Name=" + name + ", Value=");
    }
    assertThat(tasks).contains("sslmode=require");
  }

  @Test
  void databasesArePrivateEncryptedSmallAndDeletedWithoutSnapshots() {
    Map<String, Map<String, Object>> dbs = resources(stacks.data(), "AWS::RDS::DBInstance");
    assertThat(dbs).hasSize(2);
    dbs.values()
        .forEach(
            r -> {
              assertThat(props(r))
                  .containsEntry("PubliclyAccessible", false)
                  .containsEntry("StorageEncrypted", true)
                  .containsEntry("DBInstanceClass", "db.t4g.micro");
              assertThat(r).containsEntry("DeletionPolicy", "Delete");
            });
  }

  @Test
  void nothingIsRetainedOrSnapshottedOnDestroy() {
    for (Stack stack : all()) {
      resources(stack, null)
          .forEach(
              (id, r) ->
                  assertThat(r.get("DeletionPolicy"))
                      .as(stack.getStackName() + "/" + id)
                      .isNotIn("Retain", "Snapshot", "RetainExceptOnCreate"));
    }
  }

  @Test
  void theGuardrailMatchesGuardrailJson() {
    JsonNode json = GuardrailStack.read(Path.of("../bedrock/guardrail.json"));
    String guardrail =
        props(resources(stacks.guardrail(), "AWS::Bedrock::Guardrail").values().iterator().next())
            .toString();

    for (JsonNode topic : json.path("topicPolicyConfig").path("topicsConfig")) {
      assertThat(guardrail).contains(topic.path("name").asString());
    }
    for (JsonNode regex : json.path("sensitiveInformationPolicyConfig").path("regexesConfig")) {
      assertThat(guardrail).contains("Pattern=" + regex.path("pattern").asString());
    }
    assertThat(guardrail)
        .contains("Name=Unrelated to banking")
        .contains("OutputEnabled=false")
        .contains("Type=PROMPT_ATTACK")
        .contains("Type=CREDIT_DEBIT_CARD_CVV");
  }

  @Test
  void alarmsDashboardAndMetricFiltersExist() {
    assertThat(resources(stacks.observability(), "AWS::CloudWatch::Alarm")).hasSize(2);
    assertThat(resources(stacks.observability(), "AWS::CloudWatch::Dashboard")).hasSize(1);
    assertThat(resources(stacks.observability(), "AWS::Logs::MetricFilter")).hasSize(4);
    assertThat(resources(stacks.services(), "AWS::Logs::LogGroup").values())
        .allSatisfy(r -> assertThat(props(r)).containsEntry("RetentionInDays", 7));
  }

  @Test
  void deployingWithoutAnAllowedCidrIsRefused() {
    App app = App.Builder.create().context(Map.of("imagesFromRegistry", "true")).build();

    assertThatThrownBy(() -> SafeLedgerApp.build(app))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allowedCidr");
  }
}
