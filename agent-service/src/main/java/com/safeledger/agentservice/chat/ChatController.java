package com.safeledger.agentservice.chat;

import com.safeledger.agentservice.session.CustomerSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

  public record ChatRequest(@NotBlank @Size(max = 2000) String message) {}

  private final AgentService agent;

  public ChatController(AgentService agent) {
    this.agent = agent;
  }

  /** The assistant's reply plus any pending actions this turn created, for Confirm buttons. */
  @PostMapping("/api/chat")
  public AgentService.TurnResult chat(
      @Valid @RequestBody ChatRequest body, HttpServletRequest request) {
    return agent.turn(CustomerSession.require(request.getSession(false)), body.message());
  }
}
