package com.safeledger.cdk;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.GatewayVpcEndpointAwsService;
import software.amazon.awscdk.services.ec2.GatewayVpcEndpointOptions;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.InterfaceVpcEndpointAwsService;
import software.amazon.awscdk.services.ec2.InterfaceVpcEndpointOptions;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.constructs.Construct;

/**
 * VPC over two AZs without a NAT gateway. The bank API, the MCP server and the databases sit in
 * isolated subnets with no internet route and reach AWS through VPC endpoints. agent-service sits
 * in a public subnet because it calls the Claude API; only the load balancer can reach it.
 */
public class NetworkStack extends Stack {

  final Vpc vpc;
  final SecurityGroup albSg;
  final SecurityGroup agentSg;
  final SecurityGroup mcpSg;
  final SecurityGroup bankSg;

  public NetworkStack(Construct scope, String id, StackProps props, Config config) {
    super(scope, id, props);

    vpc =
        Vpc.Builder.create(this, "Vpc")
            .maxAzs(2)
            .natGateways(0)
            .subnetConfiguration(
                List.of(
                    SubnetConfiguration.builder()
                        .name("public")
                        .subnetType(SubnetType.PUBLIC)
                        .cidrMask(24)
                        .build(),
                    SubnetConfiguration.builder()
                        .name("isolated")
                        .subnetType(SubnetType.PRIVATE_ISOLATED)
                        .cidrMask(24)
                        .build()))
            .build();

    // ECR image layers come from S3; the gateway endpoint is free.
    vpc.addGatewayEndpoint(
        "S3", GatewayVpcEndpointOptions.builder().service(GatewayVpcEndpointAwsService.S3).build());

    SecurityGroup endpointSg =
        SecurityGroup.Builder.create(this, "EndpointSg")
            .vpc(vpc)
            .description("VPC interface endpoints: HTTPS from inside the VPC")
            .allowAllOutbound(false)
            .build();
    endpointSg.addIngressRule(Peer.ipv4(vpc.getVpcCidrBlock()), Port.tcp(443), "HTTPS from VPC");
    // One AZ only: endpoints are billed per AZ, and this demo does not need AZ failover.
    SubnetSelection oneAz =
        SubnetSelection.builder().subnets(List.of(vpc.getIsolatedSubnets().getFirst())).build();
    Map<String, InterfaceVpcEndpointAwsService> endpoints =
        Map.of(
            "Ecr", InterfaceVpcEndpointAwsService.ECR,
            "EcrDocker", InterfaceVpcEndpointAwsService.ECR_DOCKER,
            "Logs", InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS,
            "SecretsManager", InterfaceVpcEndpointAwsService.SECRETS_MANAGER,
            "BedrockRuntime", InterfaceVpcEndpointAwsService.BEDROCK_RUNTIME);
    endpoints.forEach(
        (name, service) ->
            vpc.addInterfaceEndpoint(
                name,
                InterfaceVpcEndpointOptions.builder()
                    .service(service)
                    .subnets(oneAz)
                    .privateDnsEnabled(true)
                    .securityGroups(List.of(endpointSg))
                    .build()));

    albSg = group("AlbSg", "Load balancer: HTTP from the allowed CIDR only", false);
    albSg.addIngressRule(Peer.ipv4(config.allowedCidr()), Port.tcp(80), "Demo users");
    agentSg = group("AgentSg", "agent-service", true);
    mcpSg = group("McpSg", "bank-mcp-server", true);
    bankSg = group("BankSg", "mock-bank-api", true);

    albSg.addEgressRule(agentSg, Port.tcp(8080), "To agent-service");
    agentSg.addIngressRule(albSg, Port.tcp(8080), "From the load balancer only");
    mcpSg.addIngressRule(agentSg, Port.tcp(8082), "From agent-service only");
    bankSg.addIngressRule(mcpSg, Port.tcp(8081), "From bank-mcp-server only");
  }

  private SecurityGroup group(String id, String description, boolean allowAllOutbound) {
    return SecurityGroup.Builder.create(this, id)
        .vpc(vpc)
        .description(description)
        .allowAllOutbound(allowAllOutbound)
        .build();
  }

  IVpc vpc() {
    return vpc;
  }
}
