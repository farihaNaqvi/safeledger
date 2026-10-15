package com.safeledger.bankmcpserver.identity;

import com.safeledger.bankmcpserver.audit.AuditLog;
import com.safeledger.bankmcpserver.config.SafeLedgerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects any request to the MCP endpoint or the internal action endpoints that does not carry a
 * valid signed identity, before any tool or handler runs. The verified identity is stored as a
 * request attribute for the MCP transport context and the controllers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IdentityFilter extends OncePerRequestFilter {

  public static final String ATTRIBUTE = "safeledger.verifiedIdentity";

  private static final Logger log = LoggerFactory.getLogger(IdentityFilter.class);

  private final SafeLedgerProperties.Identity config;
  private final AuditLog audit;

  public IdentityFilter(SafeLedgerProperties properties, AuditLog audit) {
    this.config = properties.identity();
    this.audit = audit;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !(path.equals("/mcp") || path.startsWith("/mcp/") || path.startsWith("/internal/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    CallerIdentity identity;
    try {
      identity =
          IdentityToken.verify(
              request.getHeader(IdentityToken.HEADER),
              config.secretBytes(),
              Instant.now(),
              config.maxAge());
    } catch (IdentityToken.InvalidIdentityException e) {
      log.warn("Identity rejected: reason={} path={}", e.getMessage(), request.getRequestURI());
      audit.identityRejected(e.getMessage(), request.getRequestURI());
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write("{\"error\":\"invalid_identity\"}");
      return;
    }
    request.setAttribute(ATTRIBUTE, identity);
    chain.doFilter(request, response);
  }
}
