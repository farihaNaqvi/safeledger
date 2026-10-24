package com.safeledger.agentservice.session;

import com.safeledger.agentservice.common.ApiException;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The logged in customer for this browser session. The session ID used with the MCP server is our
 * own random value, not the servlet container's session ID.
 */
public record CustomerSession(UUID customerId, String customerName, String sessionId)
    implements Serializable {

  static final String ATTRIBUTE = "safeledger.customerSession";

  public static CustomerSession require(HttpSession httpSession) {
    Object value = httpSession == null ? null : httpSession.getAttribute(ATTRIBUTE);
    if (value instanceof CustomerSession session) {
      return session;
    }
    throw new ApiException(HttpStatus.UNAUTHORIZED, "NOT_LOGGED_IN", "Log in first");
  }
}
