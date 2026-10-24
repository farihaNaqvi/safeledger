package com.safeledger.bankmcpserver.audit;

import com.safeledger.bankmcpserver.common.Json;
import com.safeledger.bankmcpserver.identity.CallerIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Append-only audit trail in the MCP server's own database. Each write is its own statement, so a
 * failing tool call or bank error never rolls its audit row back.
 */
@Component
public class AuditLog {

  /** Long digit runs could be card or account numbers typed by a user; never store them. */
  private static final Pattern LONG_NUMBER = Pattern.compile("\\d{9,19}");

  private static final int MAX_TEXT = 500;

  public enum EventType {
    TOOL_CALL,
    IDENTITY_REJECTED,
    ACTION_CREATED,
    ACTION_CONFIRMED,
    ACTION_EXECUTED,
    ACTION_FAILED,
    ACTION_CANCELLED,
    ACTION_EXPIRED,
    ACTION_REJECTED,
    INJECTION_FLAGGED
  }

  private final JdbcTemplate jdbc;
  private final MeterRegistry meters;

  public AuditLog(JdbcTemplate jdbc, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.meters = meters;
  }

  public void toolCall(
      CallerIdentity caller,
      String tool,
      Map<String, Object> arguments,
      String outcome,
      String detail,
      long latencyMs) {
    insert(EventType.TOOL_CALL, caller, tool, null, mask(arguments), outcome, detail, latencyMs);
  }

  public void action(
      EventType type, CallerIdentity caller, String tool, UUID actionId, String outcome) {
    insert(type, caller, tool, actionId, null, outcome, null, null);
    countAction(type, tool);
  }

  public void actionExpired(UUID actionId) {
    insert(EventType.ACTION_EXPIRED, null, null, actionId, null, "EXPIRED", null, null);
    countAction(EventType.ACTION_EXPIRED, null);
  }

  private void countAction(EventType type, String tool) {
    meters
        .counter(
            "safeledger.actions",
            "event",
            type.name().substring("ACTION_".length()).toLowerCase(Locale.ROOT),
            "tool",
            tool == null ? "unknown" : tool)
        .increment();
  }

  public void injectionFlagged(CallerIdentity caller, String tool, String field, String rule) {
    for (String one : rule.split(",")) {
      meters.counter("safeledger.injection.flags", "tool", tool, "rule", one.trim()).increment();
    }
    insert(
        EventType.INJECTION_FLAGGED,
        caller,
        tool,
        null,
        null,
        "FLAGGED",
        field + ": " + rule,
        null);
  }

  public void identityRejected(String reason, String path) {
    meters.counter("safeledger.identity.rejected").increment();
    insert(
        EventType.IDENTITY_REJECTED, null, null, null, null, "REJECTED", reason + " " + path, null);
  }

  /** Arguments as stored: long digit runs redacted, free text truncated. */
  static Map<String, Object> mask(Map<String, Object> arguments) {
    if (arguments == null) {
      return Map.of();
    }
    Map<String, Object> masked = new LinkedHashMap<>();
    arguments.forEach(
        (key, value) -> {
          if (value == null) {
            masked.put(key, null);
            return;
          }
          String text = LONG_NUMBER.matcher(String.valueOf(value)).replaceAll("[REDACTED]");
          masked.put(key, truncate(text));
        });
    return masked;
  }

  private void insert(
      EventType type,
      CallerIdentity caller,
      String tool,
      UUID actionId,
      Map<String, Object> arguments,
      String outcome,
      String detail,
      Long latencyMs) {
    jdbc.update(
        "insert into audit_event (event_type, session_id, customer_id, tool_name, action_id,"
            + " arguments, outcome, detail, latency_ms, correlation_id)"
            + " values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
        type.name(),
        caller == null ? null : caller.sessionId(),
        caller == null ? null : caller.customerId(),
        tool,
        actionId,
        arguments == null ? null : Json.write(arguments),
        outcome,
        truncate(detail),
        latencyMs == null ? null : Math.toIntExact(Math.min(latencyMs, Integer.MAX_VALUE)),
        MDC.get("correlationId"));
  }

  private static String truncate(String text) {
    return text == null || text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
  }
}
