package com.safeledger.agentservice.chat;

import com.safeledger.agentservice.session.CustomerSession;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

  public record ChatRequest(@NotBlank @Size(max = 2000) String message) {}

  /**
   * Lets the page send Confirm and Cancel in the same trace as the turn that created the action.
   */
  static final String TRACE_HEADER = "X-Trace-Id";

  private final AgentService agent;
  private final Tracer tracer;

  public ChatController(AgentService agent, ObjectProvider<Tracer> tracer) {
    this.agent = agent;
    this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
  }

  /** The assistant's reply plus any pending actions this turn created, for Confirm buttons. */
  @PostMapping("/api/chat")
  public AgentService.TurnResult chat(
      @Valid @RequestBody ChatRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    Span span = tracer.currentSpan();
    if (span != null) {
      response.setHeader(TRACE_HEADER, span.context().traceId());
    }
    return agent.turn(CustomerSession.require(request.getSession(false)), body.message());
  }
}
