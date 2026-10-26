package com.safeledger.agentservice.mcp;

import com.safeledger.agentservice.common.BrowserRequestFilter;
import com.safeledger.agentservice.config.AgentProperties;
import com.safeledger.agentservice.identity.IdentitySigner;
import com.safeledger.agentservice.session.CustomerSession;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the MCP server's internal action endpoints. These are reached only from the browser API
 * (the customer pressing Confirm or Cancel), never from the model's tool loop.
 */
@Component
public class ActionsClient {

  public record Action(
      UUID id,
      String tool,
      String summary,
      String payeeNote,
      String status,
      Instant createdAt,
      Instant expiresAt,
      Instant executedAt,
      Map<String, Object> result) {}

  /** Status code and body exactly as the MCP server returned them. */
  public record Outcome(int status, Object body) {}

  private final RestClient client;
  private final IdentitySigner signer;

  public ActionsClient(
      RestClient.Builder builder, AgentProperties properties, IdentitySigner signer) {
    this.client = builder.baseUrl(properties.mcpUrl()).build();
    this.signer = signer;
  }

  public List<Action> list(CustomerSession session) {
    Action[] actions =
        client
            .get()
            .uri("/internal/actions")
            .headers(h -> headers(h, session))
            .retrieve()
            .body(Action[].class);
    return actions == null ? List.of() : Arrays.asList(actions);
  }

  public Outcome confirm(CustomerSession session, UUID actionId) {
    return post(session, "/internal/actions/{id}/confirm", actionId);
  }

  public Outcome cancel(CustomerSession session, UUID actionId) {
    return post(session, "/internal/actions/{id}/cancel", actionId);
  }

  /**
   * Records what the customer typed, before the model sees it. The MCP server only lets a transfer,
   * dispute or unfreeze through when its key values appear in these words.
   */
  public void recordUtterance(CustomerSession session, String text) {
    client
        .post()
        .uri("/internal/utterances")
        .headers(h -> headers(h, session))
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("text", text))
        .retrieve()
        .toBodilessEntity();
  }

  private Outcome post(CustomerSession session, String path, UUID actionId) {
    ResponseEntity<Object> response =
        client
            .post()
            .uri(path, actionId)
            .headers(h -> headers(h, session))
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {})
            .toEntity(Object.class);
    return new Outcome(response.getStatusCode().value(), response.getBody());
  }

  private void headers(HttpHeaders h, CustomerSession session) {
    h.set(IdentitySigner.HEADER, signer.sign(session.customerId(), session.sessionId()));
    String correlationId = MDC.get(BrowserRequestFilter.MDC_KEY);
    if (correlationId != null) {
      h.set(BrowserRequestFilter.CORRELATION_HEADER, correlationId);
    }
  }
}
