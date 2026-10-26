package com.safeledger.bankmcpserver.policy;

import com.safeledger.bankmcpserver.action.PendingActionRepository;
import com.safeledger.bankmcpserver.config.SafeLedgerProperties;
import com.safeledger.bankmcpserver.identity.CallerIdentity;
import com.safeledger.bankmcpserver.tools.ToolError;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Configurable limits. Counters come from the database (audit trail and pending actions), so they
 * hold across restarts and across several server instances. Each breach is a {@link ToolError}
 * whose message the model can explain to the customer.
 */
@Service
public class LimitService {

  private final SafeLedgerProperties properties;
  private final SafeLedgerProperties.Limits limits;
  private final PendingActionRepository actions;
  private final JdbcTemplate jdbc;

  public LimitService(
      SafeLedgerProperties properties, PendingActionRepository actions, JdbcTemplate jdbc) {
    this.properties = properties;
    this.limits = properties.limits();
    this.actions = actions;
    this.jdbc = jdbc;
  }

  /** Per-session rate limit on all tools, counted over the last minute. */
  public void checkRate(CallerIdentity caller, Instant now) {
    int recent =
        count(
            "select count(*) from audit_event where event_type = 'TOOL_CALL' and session_id = ?"
                + " and occurred_at > ?",
            caller.sessionId(),
            Timestamp.from(now.minus(Duration.ofMinutes(1))));
    if (recent >= limits.maxToolCallsPerMinute()) {
      throw new ToolError(
          "RATE_LIMITED",
          "Too many requests in this session. Ask the customer to wait a minute and try again.");
    }
  }

  /** Every write tool call counts, including rejected ones, so retries cannot get around it. */
  public void checkWriteRequests(CallerIdentity caller) {
    int writes =
        count(
            "select count(*) from audit_event where event_type = 'TOOL_CALL' and session_id = ?"
                + " and tool_name like 'request\\_%'",
            caller.sessionId());
    if (writes >= limits.maxWriteRequestsPerSession()) {
      throw new ToolError(
          "SESSION_WRITE_LIMIT",
          "This session has reached its limit of "
              + limits.maxWriteRequestsPerSession()
              + " change requests. The customer can start a new session or use the bank's app.");
    }
  }

  /**
   * Pending actions are limited per customer so opening more sessions does not help. A new transfer
   * supersedes the session's waiting transfer, so that one is not counted against it.
   */
  public void checkPendingActions(CallerIdentity caller, String tool, Instant now) {
    String superseded = "request_transfer".equals(tool) ? caller.sessionId() : null;
    if (actions.countOpen(caller.customerId(), now, superseded) >= limits.maxPendingActions()) {
      throw new ToolError(
          "TOO_MANY_PENDING",
          "The customer already has "
              + limits.maxPendingActions()
              + " requests waiting for confirmation. They need to confirm or cancel one first.");
    }
  }

  /**
   * Per-action and per-day transfer limits. Pending, confirmed and executed transfers all count
   * toward the day, so splitting one large transfer into several small ones does not help. The
   * session's own waiting transfer is the exception: the new one replaces it.
   */
  public void checkTransfer(CallerIdentity caller, BigDecimal amount, Instant now) {
    if (amount.compareTo(limits.maxTransferPerAction()) > 0) {
      throw new ToolError(
          "TRANSFER_LIMIT",
          "A single transfer can be at most INR "
              + limits.maxTransferPerAction().toPlainString()
              + ".");
    }
    Instant startOfDay =
        LocalDate.now(properties.zone()).atStartOfDay(properties.zone()).toInstant();
    BigDecimal today =
        actions.transferTotalSince(caller.customerId(), startOfDay, now, caller.sessionId());
    if (today.add(amount).compareTo(limits.maxTransferPerDay()) > 0) {
      throw new ToolError(
          "DAILY_TRANSFER_LIMIT",
          "This transfer would exceed the daily limit of INR "
              + limits.maxTransferPerDay().toPlainString()
              + ". INR "
              + limits.maxTransferPerDay().subtract(today).max(BigDecimal.ZERO).toPlainString()
              + " is left for today.");
    }
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }
}
