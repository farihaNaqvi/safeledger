package com.safeledger.cdk;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;

/**
 * SafeLedger on AWS: network, data, guardrail, services, observability. Deploy and destroy with the
 * steps in the README; everything here is billed while it exists.
 */
public final class SafeLedgerApp {

  public static void main(String[] args) {
    App app = new App();
    build(app);
    app.synth();
  }

  record Stacks(
      NetworkStack network,
      DataStack data,
      GuardrailStack guardrail,
      ServicesStack services,
      ObservabilityStack observability) {}

  static Stacks build(App app) {
    Config config = Config.from(app.getNode());
    String region = System.getenv().getOrDefault("CDK_DEFAULT_REGION", "us-east-1");
    StackProps props =
        StackProps.builder()
            .env(
                Environment.builder()
                    .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                    .region(region)
                    .build())
            .build();

    NetworkStack network = new NetworkStack(app, "SafeLedger-Network", props, config);
    DataStack data = new DataStack(app, "SafeLedger-Data", props, network);
    GuardrailStack guardrail = new GuardrailStack(app, "SafeLedger-Guardrail", props, config);
    ServicesStack services =
        new ServicesStack(app, "SafeLedger-Services", props, config, network, data, guardrail);
    ObservabilityStack observability =
        new ObservabilityStack(app, "SafeLedger-Observability", props, config, services);

    // Every resource is tagged, so a leftover check after destroy can find anything missed.
    Tags.of(app).add("Project", "SafeLedger");
    return new Stacks(network, data, guardrail, services, observability);
  }

  private SafeLedgerApp() {}
}
