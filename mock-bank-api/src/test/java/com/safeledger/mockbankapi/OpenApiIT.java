package com.safeledger.mockbankapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The committed api/bank-api.yaml must match what the running app generates. To regenerate after an
 * API change: {@code mvn -pl mock-bank-api verify -Dopenapi.update=true}, then commit the file.
 */
class OpenApiIT extends IntegrationTestBase {

  private static final Path COMMITTED = Path.of("..", "api", "bank-api.yaml");

  @Test
  void committedOpenApiDocumentIsUpToDate() throws IOException {
    Response response = get("/v3/api-docs.yaml", null);
    assertThat(response.status()).isEqualTo(200);
    String generated = normalize(response.body());

    assertThat(generated)
        .contains(
            "/customers/{customerId}/accounts",
            "/accounts/{accountId}/balance",
            "/customers/{customerId}/cards",
            "/cards/{cardId}/freeze",
            "/cards/{cardId}/unfreeze",
            "/accounts/{accountId}/transactions",
            "/customers/{customerId}/payees",
            "/transfers",
            "/disputes",
            "/customers/{customerId}/disputes");

    if (Boolean.getBoolean("openapi.update")) {
      Files.createDirectories(COMMITTED.getParent());
      Files.writeString(COMMITTED, generated, StandardCharsets.UTF_8);
    }

    assertThat(Files.exists(COMMITTED))
        .as("api/bank-api.yaml is missing; run with -Dopenapi.update=true")
        .isTrue();
    assertThat(normalize(Files.readString(COMMITTED, StandardCharsets.UTF_8)))
        .as("api/bank-api.yaml is out of date; run with -Dopenapi.update=true and commit it")
        .isEqualTo(generated);
  }

  private static String normalize(String yaml) {
    String lf = yaml.replace("\r\n", "\n");
    return lf.endsWith("\n") ? lf : lf + "\n";
  }
}
