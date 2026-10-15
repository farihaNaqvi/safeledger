package com.safeledger.mockbankapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Plain REST API that owns the bank data in Postgres. Knows nothing about AI, MCP or Bedrock — see
 * {@code docs/SPEC.md} section 2.
 */
@SpringBootApplication
public class MockBankApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(MockBankApiApplication.class, args);
  }
}
