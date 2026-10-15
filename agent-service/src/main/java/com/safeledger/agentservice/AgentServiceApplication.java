package com.safeledger.agentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Customer-facing API and minimal web chat page. Uses Spring AI {@code ChatClient} with the Bedrock
 * Converse model, connects to {@code bank-mcp-server} as an MCP client, applies Bedrock Guardrails,
 * and manages sessions and the confirmation flow — see {@code docs/SPEC.md} section 2. That wiring
 * is added in Phase 4; this application is currently the Phase 1 skeleton.
 */
@SpringBootApplication
public class AgentServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(AgentServiceApplication.class, args);
  }
}
