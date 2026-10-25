package com.safeledger.bankmcpserver.provenance;

import com.safeledger.bankmcpserver.identity.CallerIdentity;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** What the customer typed in a session, as recorded by agent-service. */
@Repository
public class CustomerUtterances {

  /** Enough history for a conversation; older messages do not ground new requests. */
  static final int RECENT = 50;

  private final JdbcTemplate jdbc;

  public CustomerUtterances(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void record(CallerIdentity caller, String text) {
    jdbc.update(
        "insert into customer_utterance (customer_id, session_id, text) values (?, ?, ?)",
        caller.customerId(),
        caller.sessionId(),
        text);
  }

  public List<String> recent(CallerIdentity caller) {
    return jdbc.queryForList(
        "select text from customer_utterance where customer_id = ? and session_id = ?"
            + " order by id desc limit ?",
        String.class,
        caller.customerId(),
        caller.sessionId(),
        RECENT);
  }
}
