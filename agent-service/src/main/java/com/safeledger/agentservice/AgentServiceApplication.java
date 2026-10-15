package com.safeledger.agentservice;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Customer-facing API and minimal web chat page. A Spring AI chat model (Ollama or Amazon Bedrock
 * Converse) uses the bank tools on bank-mcp-server through an MCP client with a signed identity per
 * session, with Bedrock Guardrails on input and output and a confirmation flow the model cannot
 * reach.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentServiceApplication {

  public static void main(String[] args) {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(AgentServiceApplication.class, args);
  }
}
