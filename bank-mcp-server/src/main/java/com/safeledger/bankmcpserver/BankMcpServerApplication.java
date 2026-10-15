package com.safeledger.bankmcpserver;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring AI MCP server over Streamable HTTP. Wraps the bank API as MCP tools and enforces the
 * policy layer: verified identity, tool tiers, pending actions, limits, idempotency and audit.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class BankMcpServerApplication {

  public static void main(String[] args) {
    // Same reason as mock-bank-api: UTC instants everywhere, and no legacy zone names sent to
    // Postgres by the JDBC driver.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(BankMcpServerApplication.class, args);
  }
}
