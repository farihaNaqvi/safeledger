package com.safeledger.cdk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ecr.assets.Platform;
import software.amazon.awscdk.services.ecs.AssetImageProps;
import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.CloudMapOptions;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.CpuArchitecture;
import software.amazon.awscdk.services.ecs.DeploymentCircuitBreaker;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.LogDrivers;
import software.amazon.awscdk.services.ecs.OperatingSystemFamily;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.RuntimePlatform;
import software.amazon.awscdk.services.ecs.Secret;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListenerRule;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.FixedResponseOptions;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.elasticloadbalancingv2.ListenerAction;
import software.amazon.awscdk.services.elasticloadbalancingv2.ListenerCondition;
import software.amazon.awscdk.services.elasticloadbalancingv2.TargetType;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.secretsmanager.ISecret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;
import software.amazon.awscdk.services.servicediscovery.PrivateDnsNamespace;
import software.constructs.Construct;

/**
 * The three services on Fargate. Only agent-service is behind the public load balancer; the MCP
 * server and the bank API are reachable inside the VPC by name (Cloud Map), each only from the
 * service that calls it. Only agent-service's task role may call Bedrock.
 */
public class ServicesStack extends Stack {

  static final String NAMESPACE = "safeledger.local";

  /** 1 GB tasks: let the heap use most of it (the JVM default is a quarter). */
  static final String JAVA_OPTIONS = "-XX:MaxRAMPercentage=75";

  final ApplicationLoadBalancer alb;
  final ApplicationTargetGroup agentTargets;
  final LogGroup agentLogs;
  final LogGroup mcpLogs;
  final LogGroup bankLogs;
  final FargateService agentService;
  final FargateService mcpService;
  final FargateService bankService;

  public ServicesStack(
      Construct scope,
      String id,
      StackProps props,
      Config config,
      NetworkStack network,
      DataStack data,
      GuardrailStack guardrail) {
    super(scope, id, props);

    Cluster cluster = Cluster.Builder.create(this, "Cluster").vpc(network.vpc).build();
    PrivateDnsNamespace namespace =
        PrivateDnsNamespace.Builder.create(this, "Namespace")
            .name(NAMESPACE)
            .vpc(network.vpc)
            .build();

    software.amazon.awscdk.services.secretsmanager.Secret hmac =
        software.amazon.awscdk.services.secretsmanager.Secret.Builder.create(this, "HmacSecret")
            .description("Signs the identity header between agent-service and bank-mcp-server")
            .generateSecretString(
                SecretStringGenerator.builder().passwordLength(48).excludePunctuation(true).build())
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

    bankLogs = logs("BankLogs");
    mcpLogs = logs("McpLogs");
    agentLogs = logs("AgentLogs");

    // ---- mock-bank-api: isolated subnet, reachable from the MCP server only ----
    FargateTaskDefinition bankTask = task("BankTask");
    bankTask.addContainer(
        "app",
        container(config, "mock-bank-api", bankLogs, 8081)
            .environment(
                Map.of(
                    "BANK_DB_URL",
                    jdbcUrl(data.bankDb.getDbInstanceEndpointAddress(), "safeledger_bank"),
                    "JAVA_TOOL_OPTIONS",
                    JAVA_OPTIONS))
            .secrets(
                Map.of(
                    "BANK_DB_USERNAME",
                    Secret.fromSecretsManager(data.bankDb.getSecret(), "username"),
                    "BANK_DB_PASSWORD",
                    Secret.fromSecretsManager(data.bankDb.getSecret(), "password")))
            .build());
    bankService = service("Bank", cluster, bankTask, network.bankSg, false, "bank", namespace);

    // ---- bank-mcp-server: isolated subnet, reachable from agent-service only ----
    FargateTaskDefinition mcpTask = task("McpTask");
    mcpTask.addContainer(
        "app",
        container(config, "bank-mcp-server", mcpLogs, 8082)
            .environment(
                Map.of(
                    "MCP_DB_URL",
                    jdbcUrl(data.mcpDb.getDbInstanceEndpointAddress(), "safeledger_mcp"),
                    "BANK_API_URL",
                    "http://bank." + NAMESPACE + ":8081",
                    "JAVA_TOOL_OPTIONS",
                    JAVA_OPTIONS))
            .secrets(
                Map.of(
                    "MCP_DB_USERNAME",
                    Secret.fromSecretsManager(data.mcpDb.getSecret(), "username"),
                    "MCP_DB_PASSWORD",
                    Secret.fromSecretsManager(data.mcpDb.getSecret(), "password"),
                    "IDENTITY_HMAC_SECRET",
                    Secret.fromSecretsManager(hmac)))
            .build());
    mcpService = service("Mcp", cluster, mcpTask, network.mcpSg, false, "mcp", namespace);

    // ---- agent-service: public subnet (outbound to the Claude API), behind the ALB ----
    FargateTaskDefinition agentTask = task("AgentTask");
    Map<String, String> agentEnv = new HashMap<>();
    agentEnv.put("MCP_SERVER_URL", "http://mcp." + NAMESPACE + ":8082");
    agentEnv.put("JAVA_TOOL_OPTIONS", JAVA_OPTIONS);
    agentEnv.put("SAFELEDGER_CHAT_MODEL", config.chatModel());
    agentEnv.put("AWS_REGION", getRegion());
    agentEnv.put("BEDROCK_GUARDRAIL_ENABLED", "true");
    agentEnv.put("BEDROCK_GUARDRAIL_ID", guardrail.guardrail.getAttrGuardrailId());
    agentEnv.put("BEDROCK_GUARDRAIL_VERSION", guardrail.version.getAttrVersion());
    if (!config.usesClaudeApi()) {
      agentEnv.put("BEDROCK_MODEL_ID", config.bedrockModelId());
    }
    Map<String, Secret> agentSecrets = new HashMap<>();
    agentSecrets.put("IDENTITY_HMAC_SECRET", Secret.fromSecretsManager(hmac));
    if (config.usesClaudeApi()) {
      ISecret apiKey =
          software.amazon.awscdk.services.secretsmanager.Secret.fromSecretNameV2(
              this, "AnthropicKey", config.anthropicSecretName());
      agentSecrets.put("ANTHROPIC_API_KEY", Secret.fromSecretsManager(apiKey));
    }
    agentTask.addContainer(
        "app",
        container(config, "agent-service", agentLogs, 8080)
            .environment(agentEnv)
            .secrets(agentSecrets)
            .build());
    agentTask
        .getTaskRole()
        .addToPrincipalPolicy(
            PolicyStatement.Builder.create()
                .actions(List.of("bedrock:ApplyGuardrail"))
                .resources(List.of(guardrail.guardrail.getAttrGuardrailArn()))
                .build());
    if (!config.usesClaudeApi()) {
      agentTask
          .getTaskRole()
          .addToPrincipalPolicy(
              PolicyStatement.Builder.create()
                  .actions(List.of("bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"))
                  .resources(
                      List.of(
                          // An inference profile (us.*, eu.*, global.*) routes to the base model
                          // in several regions; both ARNs are needed, for this model only.
                          "arn:aws:bedrock:*::foundation-model/"
                              + config.bedrockModelId().replaceFirst("^(us|eu|apac|global)\\.", ""),
                          "arn:aws:bedrock:"
                              + getRegion()
                              + ":"
                              + getAccount()
                              + ":inference-profile/"
                              + config.bedrockModelId()))
                  .build());
    }
    agentService = service("Agent", cluster, agentTask, network.agentSg, true, null, null);

    alb =
        ApplicationLoadBalancer.Builder.create(this, "Alb")
            .vpc(network.vpc)
            .internetFacing(true)
            .securityGroup(network.albSg)
            .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
            .build();
    agentTargets =
        ApplicationTargetGroup.Builder.create(this, "AgentTargets")
            .vpc(network.vpc)
            .port(8080)
            .protocol(ApplicationProtocol.HTTP)
            .targetType(TargetType.IP)
            .targets(List.of(agentService))
            .healthCheck(
                HealthCheck.builder()
                    .path("/actuator/health")
                    .healthyHttpCodes("200")
                    .interval(Duration.seconds(15))
                    .build())
            .deregistrationDelay(Duration.seconds(10))
            .build();
    // open(false): the security group already limits callers to the allowed CIDR.
    ApplicationListener listener =
        alb.addListener(
            "Http",
            software.amazon.awscdk.services.elasticloadbalancingv2.BaseApplicationListenerProps
                .builder()
                .port(80)
                .open(false)
                .defaultAction(ListenerAction.forward(List.of(agentTargets)))
                .build());
    // Metrics and health stay inside the VPC.
    ApplicationListenerRule.Builder.create(this, "BlockActuator")
        .listener(listener)
        .priority(1)
        .conditions(List.of(ListenerCondition.pathPatterns(List.of("/actuator", "/actuator/*"))))
        .action(
            ListenerAction.fixedResponse(
                404, FixedResponseOptions.builder().contentType("text/plain").build()))
        .build();

    CfnOutput.Builder.create(this, "DemoUrl")
        .value("http://" + alb.getLoadBalancerDnsName())
        .description("Open in a browser from the allowed CIDR")
        .build();
  }

  private LogGroup logs(String id) {
    return LogGroup.Builder.create(this, id)
        .retention(RetentionDays.ONE_WEEK)
        .removalPolicy(RemovalPolicy.DESTROY)
        .build();
  }

  private FargateTaskDefinition task(String id) {
    return FargateTaskDefinition.Builder.create(this, id)
        .cpu(512)
        .memoryLimitMiB(1024)
        .runtimePlatform(
            RuntimePlatform.builder()
                .cpuArchitecture(CpuArchitecture.X86_64)
                .operatingSystemFamily(OperatingSystemFamily.LINUX)
                .build())
        .build();
  }

  private ContainerDefinitionOptions.Builder container(
      Config config, String module, LogGroup logGroup, int port) {
    ContainerImage image =
        config.imagesFromRegistry()
            ? ContainerImage.fromRegistry("public.ecr.aws/docker/library/busybox:1.37")
            : ContainerImage.fromAsset(
                config.repoRoot().toString(),
                AssetImageProps.builder()
                    .file(module + "/Dockerfile")
                    .platform(Platform.LINUX_AMD64)
                    .build());
    return ContainerDefinitionOptions.builder()
        .image(image)
        .logging(
            LogDrivers.awsLogs(
                AwsLogDriverProps.builder().logGroup(logGroup).streamPrefix(module).build()))
        .portMappings(List.of(PortMapping.builder().containerPort(port).build()));
  }

  private FargateService service(
      String id,
      Cluster cluster,
      FargateTaskDefinition task,
      SecurityGroup sg,
      boolean publicSubnet,
      String dnsName,
      PrivateDnsNamespace namespace) {
    FargateService.Builder b =
        FargateService.Builder.create(this, id + "Service")
            .cluster(cluster)
            .taskDefinition(task)
            .desiredCount(1)
            .minHealthyPercent(0)
            .securityGroups(List.of(sg))
            .assignPublicIp(publicSubnet)
            .vpcSubnets(
                SubnetSelection.builder()
                    .subnetType(publicSubnet ? SubnetType.PUBLIC : SubnetType.PRIVATE_ISOLATED)
                    .build())
            // A task that keeps failing rolls back within minutes instead of retrying for hours.
            .circuitBreaker(DeploymentCircuitBreaker.builder().rollback(true).build());
    if (publicSubnet) {
      b.healthCheckGracePeriod(Duration.seconds(180));
    }
    if (dnsName != null) {
      b.cloudMapOptions(
          CloudMapOptions.builder().name(dnsName).cloudMapNamespace(namespace).build());
    }
    return b.build();
  }

  private static String jdbcUrl(String host, String database) {
    return "jdbc:postgresql://" + host + ":5432/" + database + "?sslmode=require";
  }
}
