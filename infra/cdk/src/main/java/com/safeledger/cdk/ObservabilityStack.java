package com.safeledger.cdk;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.Dashboard;
import software.amazon.awscdk.services.cloudwatch.GraphWidget;
import software.amazon.awscdk.services.cloudwatch.IMetric;
import software.amazon.awscdk.services.cloudwatch.MathExpression;
import software.amazon.awscdk.services.cloudwatch.Metric;
import software.amazon.awscdk.services.cloudwatch.SingleValueWidget;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
import software.amazon.awscdk.services.elasticloadbalancingv2.HttpCodeElb;
import software.amazon.awscdk.services.elasticloadbalancingv2.HttpCodeTarget;
import software.amazon.awscdk.services.logs.FilterPattern;
import software.amazon.awscdk.services.logs.IFilterPattern;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.MetricFilter;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
import software.constructs.Construct;

/**
 * CloudWatch dashboard and alarms. Application metrics come from metric filters on the services'
 * structured logs, so no metrics agent or extra VPC endpoint is needed.
 */
public class ObservabilityStack extends Stack {

  static final String NAMESPACE = "SafeLedger";

  final Alarm errorRate;
  final Alarm injectionSpike;

  public ObservabilityStack(
      Construct scope, String id, StackProps props, Config config, ServicesStack services) {
    super(scope, id, props);

    Metric injectionFlags =
        fromLogs(
            "InjectionFlags",
            services.mcpLogs,
            FilterPattern.stringValue("$.message", "=", "Security event: injection pattern*"),
            "1");
    Metric guardrail =
        fromLogs(
            "GuardrailInterventions",
            services.agentLogs,
            FilterPattern.stringValue("$.message", "=", "Security event: guardrail*"),
            "1");
    Metric cost =
        fromLogs(
            "ModelCostUsd",
            services.agentLogs,
            FilterPattern.stringValue("$.message", "=", "Turn finished*"),
            "$.costUsd");
    Metric sessions =
        fromLogs(
            "ChatSessions",
            services.agentLogs,
            FilterPattern.all(
                FilterPattern.stringValue("$.message", "=", "Turn finished*"),
                FilterPattern.booleanValue("$.firstTurn", true)),
            "1");
    MathExpression costPerSession =
        MathExpression.Builder.create()
            .expression("IF(sessions > 0, cost / sessions, 0)")
            .usingMetrics(Map.of("cost", cost, "sessions", sessions))
            .label("Cost per session (USD)")
            .build();

    Metric requests = services.alb.getMetrics().requestCount();
    MathExpression errorPercent =
        MathExpression.Builder.create()
            .expression("IF(requests > 0, 100 * (target5xx + elb5xx) / requests, 0)")
            .usingMetrics(
                Map.of(
                    "requests", requests,
                    "target5xx",
                        services.alb.getMetrics().httpCodeTarget(HttpCodeTarget.TARGET_5XX_COUNT),
                    "elb5xx", services.alb.getMetrics().httpCodeElb(HttpCodeElb.ELB_5XX_COUNT)))
            .label("5xx %")
            .period(Duration.minutes(5))
            .build();

    errorRate =
        Alarm.Builder.create(this, "ErrorRate")
            .alarmDescription("More than 5% of requests failed with a 5xx over 5 minutes")
            .metric(errorPercent)
            .threshold(5)
            .evaluationPeriods(1)
            .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
            .treatMissingData(TreatMissingData.NOT_BREACHING)
            .build();
    injectionSpike =
        Alarm.Builder.create(this, "InjectionSpike")
            .alarmDescription("More than 10 injection flags in 5 minutes")
            .metric(
                injectionFlags.with(
                    software.amazon.awscdk.services.cloudwatch.MetricOptions.builder()
                        .period(Duration.minutes(5))
                        .build()))
            .threshold(10)
            .evaluationPeriods(1)
            .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
            .treatMissingData(TreatMissingData.NOT_BREACHING)
            .build();
    if (config.alarmEmail() != null) {
      Topic topic = Topic.Builder.create(this, "Alarms").build();
      topic.addSubscription(new EmailSubscription(config.alarmEmail()));
      errorRate.addAlarmAction(new SnsAction(topic));
      injectionSpike.addAlarmAction(new SnsAction(topic));
    }

    Dashboard dashboard =
        Dashboard.Builder.create(this, "Dashboard").dashboardName("SafeLedger").build();
    dashboard.addWidgets(
        single("Cost per session (USD)", costPerSession),
        single("Model cost (USD)", cost),
        single("Sessions that chatted", sessions),
        single("Injection flags", injectionFlags),
        single("Guardrail interventions", guardrail));
    dashboard.addWidgets(
        graph("Requests and 5xx %", List.of(requests), List.of(errorPercent)),
        graph(
            "Target response time (s)",
            List.of(services.alb.getMetrics().targetResponseTime()),
            List.of()));
    dashboard.addWidgets(
        graph("Injection flags", List.of(injectionFlags), List.of()),
        graph("Guardrail interventions", List.of(guardrail), List.of()));
    dashboard.addWidgets(
        graph(
            "CPU % by service",
            List.of(
                services.agentService.metricCpuUtilization(),
                services.mcpService.metricCpuUtilization(),
                services.bankService.metricCpuUtilization()),
            List.of()),
        graph(
            "Memory % by service",
            List.of(
                services.agentService.metricMemoryUtilization(),
                services.mcpService.metricMemoryUtilization(),
                services.bankService.metricMemoryUtilization()),
            List.of()));
  }

  private Metric fromLogs(String name, LogGroup logGroup, IFilterPattern pattern, String value) {
    MetricFilter filter =
        MetricFilter.Builder.create(this, name + "Filter")
            .logGroup(logGroup)
            .filterPattern(pattern)
            .metricNamespace(NAMESPACE)
            .metricName(name)
            .metricValue(value)
            .defaultValue(0)
            .build();
    return filter.metric(
        software.amazon.awscdk.services.cloudwatch.MetricOptions.builder()
            .statistic("Sum")
            .build());
  }

  private static SingleValueWidget single(String title, IMetric metric) {
    return SingleValueWidget.Builder.create()
        .title(title)
        .metrics(List.of(metric))
        .setPeriodToTimeRange(true)
        .width(4)
        .build();
  }

  private static GraphWidget graph(String title, List<IMetric> left, List<IMetric> right) {
    return GraphWidget.Builder.create().title(title).left(left).right(right).width(12).build();
  }
}
