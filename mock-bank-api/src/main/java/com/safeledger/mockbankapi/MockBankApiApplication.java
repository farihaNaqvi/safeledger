package com.safeledger.mockbankapi;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Plain REST API that owns the synthetic bank data in Postgres. Knows nothing about AI. */
@SpringBootApplication
public class MockBankApiApplication {

  public static void main(String[] args) {
    // Timestamps are stored and compared as UTC instants. A fixed default zone also stops the JDBC
    // driver sending legacy zone names (e.g. Asia/Calcutta) that newer Postgres images reject.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(MockBankApiApplication.class, args);
  }
}
