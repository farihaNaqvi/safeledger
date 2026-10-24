package com.safeledger.cdk;

import java.util.List;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine;
import software.amazon.awscdk.services.rds.PostgresEngineVersion;
import software.amazon.awscdk.services.rds.PostgresInstanceEngineProps;
import software.amazon.awscdk.services.rds.StorageType;
import software.constructs.Construct;

/**
 * One small Postgres instance per service, so the bank data and the MCP server's audit trail have
 * separate credentials (locally they are two databases on one server). All data is synthetic: the
 * instances are deleted on destroy without a final snapshot.
 */
public class DataStack extends Stack {

  final DatabaseInstance bankDb;
  final DatabaseInstance mcpDb;

  public DataStack(Construct scope, String id, StackProps props, NetworkStack network) {
    super(scope, id, props);
    bankDb = database("BankDb", "safeledger_bank", network, network.bankSg);
    mcpDb = database("McpDb", "safeledger_mcp", network, network.mcpSg);
  }

  private DatabaseInstance database(
      String id, String name, NetworkStack network, SecurityGroup client) {
    SecurityGroup sg =
        SecurityGroup.Builder.create(this, id + "Sg")
            .vpc(network.vpc)
            .description(name + ": Postgres from its service only")
            .allowAllOutbound(false)
            .build();
    sg.addIngressRule(client, Port.tcp(5432), "From its service only");
    return DatabaseInstance.Builder.create(this, id)
        .engine(
            DatabaseInstanceEngine.postgres(
                PostgresInstanceEngineProps.builder()
                    .version(PostgresEngineVersion.VER_16)
                    .build()))
        .instanceType(InstanceType.of(InstanceClass.T4G, InstanceSize.MICRO))
        .vpc(network.vpc)
        .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PRIVATE_ISOLATED).build())
        .securityGroups(List.of(sg))
        .credentials(Credentials.fromGeneratedSecret("safeledger"))
        .databaseName(name)
        .allocatedStorage(20)
        .storageType(StorageType.GP3)
        .multiAz(false)
        .publiclyAccessible(false)
        .storageEncrypted(true)
        .backupRetention(Duration.days(0))
        .deletionProtection(false)
        .removalPolicy(RemovalPolicy.DESTROY)
        .build();
  }
}
