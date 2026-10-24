package com.safeledger.bankmcpserver.tools;

import com.safeledger.bankmcpserver.audit.AuditLog;
import com.safeledger.bankmcpserver.bank.BankApiException;
import com.safeledger.bankmcpserver.common.CorrelationIdFilter;
import com.safeledger.bankmcpserver.common.Json;
import com.safeledger.bankmcpserver.identity.CallerIdentity;
import com.safeledger.bankmcpserver.identity.McpTransportConfig;
import com.safeledger.bankmcpserver.policy.LimitService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Wraps every tool call: identity from the transport context only, rate and session limits, audit,
 * and turning failures into clear error results the model can relay.
 */
@Component
public class ToolRunner {

  private static final Logger log = LoggerFactory.getLogger(ToolRunner.class);

  private final LimitService limits;
  private final AuditLog audit;
  private final ObservationRegistry observations;

  public ToolRunner(LimitService limits, AuditLog audit, ObservationRegistry observations) {
    this.limits = limits;
    this.audit = audit;
    this.observations = observations;
  }

  public CallToolResult read(
      String tool,
      McpTransportContext context,
      Map<String, Object> arguments,
      Function<CallerIdentity, Object> body) {
    return run(tool, context, arguments, false, body);
  }

  public CallToolResult write(
      String tool,
      McpTransportContext context,
      Map<String, Object> arguments,
      Function<CallerIdentity, Object> body) {
    return run(tool, context, arguments, true, body);
  }

  private CallToolResult run(
      String tool,
      McpTransportContext context,
      Map<String, Object> arguments,
      boolean write,
      Function<CallerIdentity, Object> body) {
    Object identity = context == null ? null : context.get(McpTransportConfig.IDENTITY_KEY);
    if (!(identity instanceof CallerIdentity caller)) {
      // The identity filter makes this unreachable over HTTP; fail closed anyway.
      return error("UNAUTHENTICATED", "No verified customer identity for this request.");
    }
    Object correlationId = context.get(McpTransportConfig.CORRELATION_KEY);
    if (correlationId != null) {
      MDC.put(CorrelationIdFilter.MDC_KEY, correlationId.toString());
    }
    // One span and one timer per tool call: safeledger.tool{tool, outcome}.
    Observation observation =
        Observation.createNotStarted("safeledger.tool", observations)
            .contextualName("tool " + tool)
            .lowCardinalityKeyValue("tool", tool)
            .lowCardinalityKeyValue("outcome", "OK");
    if (context.get(McpTransportConfig.OBSERVATION_KEY) instanceof Observation parent) {
      observation.parentObservation(parent);
    }
    observation.start();
    try (Observation.Scope scope = observation.openScope()) {
      return execute(tool, caller, arguments, write, body, observation);
    } finally {
      observation.stop();
      MDC.remove(CorrelationIdFilter.MDC_KEY);
    }
  }

  private CallToolResult execute(
      String tool,
      CallerIdentity caller,
      Map<String, Object> arguments,
      boolean write,
      Function<CallerIdentity, Object> body,
      Observation observation) {
    long started = System.nanoTime();
    try {
      limits.checkRate(caller, Instant.now());
      if (write) {
        limits.checkWriteRequests(caller);
      }
      Object result = body.apply(caller);
      audit.toolCall(caller, tool, arguments, "OK", null, elapsedMs(started));
      return CallToolResult.builder().addTextContent(Json.write(result)).build();
    } catch (ToolError e) {
      observation.lowCardinalityKeyValue("outcome", e.code());
      audit.toolCall(caller, tool, arguments, e.code(), e.getMessage(), elapsedMs(started));
      return error(e.code(), e.getMessage());
    } catch (BankApiException e) {
      ToolError mapped = fromBank(e);
      observation.lowCardinalityKeyValue("outcome", mapped.code());
      audit.toolCall(caller, tool, arguments, mapped.code(), e.getMessage(), elapsedMs(started));
      return error(mapped.code(), mapped.getMessage());
    } catch (RuntimeException e) {
      log.error("Tool failed: tool={}", tool, e);
      observation.lowCardinalityKeyValue("outcome", "INTERNAL_ERROR");
      observation.error(e);
      audit.toolCall(caller, tool, arguments, "INTERNAL_ERROR", null, elapsedMs(started));
      return error(
          "INTERNAL_ERROR", "The bank service could not complete this request. Try again later.");
    }
  }

  private static ToolError fromBank(BankApiException e) {
    if (e.isNotFound()) {
      return new ToolError("NOT_FOUND", "Nothing with that ID belongs to this customer.");
    }
    if (e.isClientError()) {
      return new ToolError(e.code() == null ? "BANK_REJECTED" : e.code(), e.getMessage());
    }
    return new ToolError("BANK_UNAVAILABLE", "The bank is not responding. Try again later.");
  }

  private static CallToolResult error(String code, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", code);
    body.put("message", message);
    return CallToolResult.builder().isError(true).addTextContent(Json.write(body)).build();
  }

  private static long elapsedMs(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }
}
