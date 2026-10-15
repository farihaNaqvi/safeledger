package com.safeledger.mockbankapi;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs the real application on a random port against a Testcontainers Postgres and talks to it over
 * HTTP, so every test exercises the same path a real caller would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
abstract class IntegrationTestBase {

  static final HttpClient HTTP = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  int port;

  @Autowired JdbcTemplate jdbc;

  record Response(int status, String body, HttpHeaders headers) {

    <T> T json(String path) {
      return JsonPath.read(body, path);
    }

    BigDecimal decimal(String path) {
      return new BigDecimal(String.valueOf((Object) json(path)));
    }

    String header(String name) {
      return headers.firstValue(name).orElse(null);
    }
  }

  Response get(String path, UUID customerId) {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
    if (customerId != null) {
      request.header("X-Customer-Id", customerId.toString());
    }
    return send(request);
  }

  Response post(String path, UUID customerId, String idempotencyKey, String jsonBody) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
    if (customerId != null) {
      request.header("X-Customer-Id", customerId.toString());
    }
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return send(request);
  }

  Response send(HttpRequest.Builder request) {
    try {
      HttpResponse<String> response =
          HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body(), response.headers());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  static String newKey() {
    return "test-" + UUID.randomUUID();
  }

  BigDecimal balanceOf(UUID accountId) {
    return jdbc.queryForObject(
        "select balance from account where id = ?", BigDecimal.class, accountId);
  }

  int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  static String transferJson(UUID fromAccountId, UUID payeeId, String amount, String note) {
    return """
        {"fromAccountId":"%s","payeeId":"%s","amount":%s%s}"""
        .formatted(
            fromAccountId, payeeId, amount, note == null ? "" : ",\"note\":\"" + note + "\"");
  }

  static String disputeJson(UUID transactionId, String reason) {
    return """
        {"transactionId":"%s","reason":"%s"}"""
        .formatted(transactionId, reason);
  }
}
