package com.safeledger.bankmcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring AI MCP server over Streamable HTTP. Wraps the bank API as MCP tools and enforces the
 * policy layer: tool tiers, pending actions, limits, idempotency, audit. MCP tool exports and the
 * policy layer are added in Phase 3; this application is currently the Phase 1 skeleton.
 */
@SpringBootApplication
public class BankMcpServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(BankMcpServerApplication.class, args);
  }
}
