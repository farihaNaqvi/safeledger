package com.safeledger.agentservice.chat;

import com.safeledger.agentservice.mcp.ActionsClient;
import com.safeledger.agentservice.session.CustomerSession;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's Confirm and Cancel buttons. These run outside the model: the browser calls them
 * directly, and they forward to the MCP server with the session's signed identity. Nothing in the
 * chat can reach them.
 */
@RestController
public class ActionController {

  private final ActionsClient actions;
  private final AgentService agent;

  public ActionController(ActionsClient actions, AgentService agent) {
    this.actions = actions;
    this.agent = agent;
  }

  @GetMapping("/api/actions")
  public List<ActionsClient.Action> list(HttpServletRequest request) {
    return actions.list(CustomerSession.require(request.getSession(false)));
  }

  @PostMapping("/api/actions/{id}/confirm")
  public ResponseEntity<Object> confirm(@PathVariable UUID id, HttpServletRequest request) {
    CustomerSession session = CustomerSession.require(request.getSession(false));
    ActionsClient.Outcome outcome = actions.confirm(session, id);
    record(session, "The customer pressed Confirm", outcome);
    return ResponseEntity.status(outcome.status()).body(outcome.body());
  }

  @PostMapping("/api/actions/{id}/cancel")
  public ResponseEntity<Object> cancel(@PathVariable UUID id, HttpServletRequest request) {
    CustomerSession session = CustomerSession.require(request.getSession(false));
    ActionsClient.Outcome outcome = actions.cancel(session, id);
    record(session, "The customer pressed Cancel", outcome);
    return ResponseEntity.status(outcome.status()).body(outcome.body());
  }

  private void record(CustomerSession session, String what, ActionsClient.Outcome outcome) {
    if (outcome.status() == 200 && outcome.body() instanceof Map<?, ?> body) {
      agent.recordAppEvent(
          session, what + " for: " + body.get("summary") + " Result: " + body.get("status") + ".");
    }
  }
}
