package com.safeledger.eval.run;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Talks to agent-service exactly like the browser page does: one cookie jar per session. */
public final class AgentClient {

  public record Pending(String id, String summary) {}

  public record ChatResult(
      int status,
      String reply,
      List<Pending> pending,
      boolean guardrailIntervened,
      String promptVersion,
      long inputTokens,
      long outputTokens) {}

  public record ActionResult(int status, String actionStatus) {}

  public record Action(String id, String status) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String baseUrl;
  private final Duration turnTimeout;
  private final HttpClient http =
      HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

  public AgentClient(String baseUrl, Duration turnTimeout) {
    this.baseUrl = baseUrl;
    this.turnTimeout = turnTimeout;
  }

  public void login(UUID customerId) {
    ObjectNode body =
        JsonNodeFactory.instance.objectNode().put("customerId", customerId.toString());
    Response r = post("/api/session", body);
    if (r.status() != 200) {
      throw new IllegalStateException("Login failed: HTTP " + r.status() + " " + r.body());
    }
  }

  public ChatResult chat(String message) {
    Response r = post("/api/chat", JsonNodeFactory.instance.objectNode().put("message", message));
    if (r.status() != 200) {
      return new ChatResult(r.status(), r.body(), List.of(), false, null, 0, 0);
    }
    JsonNode json = JSON.readTree(r.body());
    List<Pending> pending = new ArrayList<>();
    for (JsonNode p : json.path("pendingActions")) {
      pending.add(new Pending(p.path("id").asString(), p.path("summary").asString()));
    }
    return new ChatResult(
        200,
        json.path("reply").asString(),
        pending,
        json.path("guardrailIntervened").asBoolean(),
        json.path("promptVersion").asString(),
        json.path("usage").path("inputTokens").asLong(),
        json.path("usage").path("outputTokens").asLong());
  }

  public List<Action> actions() {
    Response r = send(request("/api/actions").GET());
    if (r.status() != 200) {
      throw new IllegalStateException("Listing actions failed: HTTP " + r.status());
    }
    List<Action> actions = new ArrayList<>();
    for (JsonNode a : JSON.readTree(r.body())) {
      actions.add(new Action(a.path("id").asString(), a.path("status").asString()));
    }
    return actions;
  }

  public ActionResult confirm(String actionId) {
    return act(actionId, "confirm");
  }

  public ActionResult cancel(String actionId) {
    return act(actionId, "cancel");
  }

  private ActionResult act(String actionId, String what) {
    Response r = post("/api/actions/" + actionId + "/" + what, null);
    String status = r.status() == 200 ? JSON.readTree(r.body()).path("status").asString() : null;
    return new ActionResult(r.status(), status);
  }

  private record Response(int status, String body) {}

  private Response post(String path, JsonNode body) {
    return send(
        request(path)
            .header("Content-Type", "application/json")
            .header("X-Requested-With", "SafeLedger")
            .POST(
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body.toString())));
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(turnTimeout);
  }

  private Response send(HttpRequest.Builder request) {
    try {
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body());
    } catch (IOException e) {
      throw new IllegalStateException("agent-service not reachable at " + baseUrl, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
