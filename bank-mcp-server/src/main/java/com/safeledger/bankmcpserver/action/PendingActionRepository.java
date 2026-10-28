package com.safeledger.bankmcpserver.action;

import com.safeledger.bankmcpserver.common.Json;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Pending action storage. Every state change is a single conditional UPDATE on the current status,
 * so two concurrent confirms (or a confirm racing the expiry job) cannot both win.
 */
@Repository
public class PendingActionRepository {

  private static final RowMapper<PendingAction> MAPPER = PendingActionRepository::map;

  private final JdbcTemplate jdbc;

  public PendingActionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(PendingAction a) {
    jdbc.update(
        "insert into pending_action (id, customer_id, session_id, tool_name, arguments, summary,"
            + " status, idempotency_key, amount, created_at, expires_at)"
            + " values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)",
        a.id(),
        a.customerId(),
        a.sessionId(),
        a.toolName(),
        Json.write(a.arguments()),
        a.summary(),
        a.status().name(),
        a.idempotencyKey(),
        a.amount(),
        Timestamp.from(a.createdAt()),
        Timestamp.from(a.expiresAt()));
  }

  public Optional<PendingAction> findOwned(UUID id, UUID customerId) {
    return jdbc
        .query(
            "select * from pending_action where id = ? and customer_id = ?", MAPPER, id, customerId)
        .stream()
        .findFirst();
  }

  public List<PendingAction> findBySession(UUID customerId, String sessionId) {
    return jdbc.query(
        "select * from pending_action where customer_id = ? and session_id = ?"
            + " order by created_at desc",
        MAPPER,
        customerId,
        sessionId);
  }

  /**
   * PENDING to CONFIRMED, only if still pending, unexpired and owned. True if this call won. Stores
   * the customer's payee note in the same update and drops any note that was there before.
   */
  public boolean markConfirmed(UUID id, UUID customerId, Instant now, String payeeNote) {
    return jdbc.update(
            "update pending_action set status = 'CONFIRMED', confirmed_at = ?,"
                + " arguments = (arguments - 'note')"
                + " || jsonb_strip_nulls(jsonb_build_object('note', ?::text))"
                + " where id = ? and customer_id = ? and status = 'PENDING' and expires_at > ?",
            Timestamp.from(now),
            payeeNote,
            id,
            customerId,
            Timestamp.from(now))
        == 1;
  }

  public boolean markCancelled(UUID id, UUID customerId) {
    return jdbc.update(
            "update pending_action set status = 'CANCELLED'"
                + " where id = ? and customer_id = ? and status = 'PENDING'",
            id,
            customerId)
        == 1;
  }

  public void markFinished(UUID id, PendingAction.Status status, String resultJson, Instant now) {
    jdbc.update(
        "update pending_action set status = ?, result = ?::jsonb, executed_at = ?"
            + " where id = ? and status = 'CONFIRMED'",
        status.name(),
        resultJson,
        Timestamp.from(now),
        id);
  }

  /** Open requests of this tool in the session, other than {@code keep}, become SUPERSEDED. */
  public List<PendingAction> supersedeOpen(
      UUID customerId, String sessionId, String toolName, UUID keep, Instant now) {
    return jdbc.query(
        "update pending_action set status = 'SUPERSEDED'"
            + " where customer_id = ? and session_id = ? and tool_name = ? and id <> ?"
            + " and status = 'PENDING' and expires_at > ? returning *",
        MAPPER,
        customerId,
        sessionId,
        toolName,
        keep,
        Timestamp.from(now));
  }

  /** Marks every overdue pending action EXPIRED and returns their IDs. */
  public List<UUID> expireOverdue(Instant now) {
    return jdbc.queryForList(
        "update pending_action set status = 'EXPIRED'"
            + " where status = 'PENDING' and expires_at <= ? returning id",
        UUID.class,
        Timestamp.from(now));
  }

  public List<PendingAction> findOpen(UUID customerId, String toolName, Instant now) {
    return jdbc.query(
        "select * from pending_action where customer_id = ? and tool_name = ?"
            + " and status = 'PENDING' and expires_at > ?",
        MAPPER,
        customerId,
        toolName,
        Timestamp.from(now));
  }

  /** Open actions, leaving out the session's transfers, which a new transfer will supersede. */
  public int countOpen(UUID customerId, Instant now, String supersededSession) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from pending_action"
                + " where customer_id = ? and status = 'PENDING' and expires_at > ?"
                + " and not (tool_name = 'request_transfer' and session_id = ?)",
            Integer.class,
            customerId,
            Timestamp.from(now),
            supersededSession == null ? "" : supersededSession);
    return n == null ? 0 : n;
  }

  /**
   * Transfers that are pending, confirmed or executed since {@code since}, summed. Pending ones in
   * {@code supersededSession} are left out: the new transfer being checked will supersede them.
   */
  public BigDecimal transferTotalSince(
      UUID customerId, Instant since, Instant now, String supersededSession) {
    BigDecimal total =
        jdbc.queryForObject(
            "select coalesce(sum(amount), 0) from pending_action where customer_id = ?"
                + " and tool_name = 'request_transfer' and created_at >= ?"
                + " and (status in ('CONFIRMED', 'EXECUTED')"
                + " or (status = 'PENDING' and expires_at > ? and session_id <> ?))",
            BigDecimal.class,
            customerId,
            Timestamp.from(since),
            Timestamp.from(now),
            supersededSession);
    return total == null ? BigDecimal.ZERO : total;
  }

  @SuppressWarnings("unchecked")
  private static PendingAction map(ResultSet rs, int row) throws SQLException {
    return new PendingAction(
        rs.getObject("id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("session_id"),
        rs.getString("tool_name"),
        Json.read(rs.getString("arguments"), Map.class),
        rs.getString("summary"),
        PendingAction.Status.valueOf(rs.getString("status")),
        rs.getString("idempotency_key"),
        rs.getBigDecimal("amount"),
        instant(rs, "created_at"),
        instant(rs, "expires_at"),
        instant(rs, "executed_at"),
        rs.getString("result"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }
}
