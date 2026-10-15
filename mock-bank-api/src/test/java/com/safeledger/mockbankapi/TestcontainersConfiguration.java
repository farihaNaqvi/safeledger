package com.safeledger.mockbankapi;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real Postgres 16 for integration tests, same major version as infra/docker-compose.yml. Every
 * test class shares one Spring context and therefore one container; Flyway migrates and seeds it on
 * startup.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:16");
  }
}
