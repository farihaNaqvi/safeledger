package com.safeledger.eval.run;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads bank-mcp-server's audit log. Every tool call is audited whatever its outcome, so this is
 * the record of what the model tried, including writes the policy layer rejected. Times come from
 * the database clock so the turn window and the audit rows use the same clock.
 */
public final class AuditReader implements AutoCloseable {

  private final Connection connection;

  public AuditReader(String jdbcUrl, String user, String password) {
    try {
      this.connection = DriverManager.getConnection(jdbcUrl, user, password);
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot connect to the MCP database at " + jdbcUrl, e);
    }
  }

  public Timestamp now() {
    try (PreparedStatement st = connection.prepareStatement("select clock_timestamp()");
        ResultSet rs = st.executeQuery()) {
      rs.next();
      return rs.getTimestamp(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Tool names called for this customer in (from, to], oldest first. */
  public List<String> toolCalls(UUID customerId, Timestamp from, Timestamp to) {
    String sql =
        "select tool_name from audit_event where event_type = 'TOOL_CALL' and customer_id = ?"
            + " and occurred_at > ? and occurred_at <= ? order by id";
    try (PreparedStatement st = connection.prepareStatement(sql)) {
      st.setObject(1, customerId);
      st.setTimestamp(2, from);
      st.setTimestamp(3, to);
      List<String> tools = new ArrayList<>();
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          tools.add(rs.getString(1));
        }
      }
      return tools;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
