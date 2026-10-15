package com.safeledger.agentservice.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Two cross-cutting checks for the browser API:
 *
 * <ul>
 *   <li>Correlation ID: taken from {@code X-Correlation-Id} or generated, put in the MDC for the
 *       structured logs, echoed on the response and forwarded to the MCP server.
 *   <li>CSRF: state-changing {@code /api/**} requests must carry {@code X-Requested-With:
 *       SafeLedger}. Browsers will not send a custom header cross-site without a CORS preflight,
 *       which this service never allows; together with the SameSite=Strict session cookie this
 *       stops another site from pressing Confirm on the customer's behalf.
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BrowserRequestFilter extends OncePerRequestFilter {

  public static final String CORRELATION_HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";
  public static final String CSRF_HEADER = "X-Requested-With";
  public static final String CSRF_VALUE = "SafeLedger";

  private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader(CORRELATION_HEADER);
    String correlationId =
        incoming != null && SAFE_ID.matcher(incoming).matches()
            ? incoming
            : UUID.randomUUID().toString();
    MDC.put(MDC_KEY, correlationId);
    response.setHeader(CORRELATION_HEADER, correlationId);
    try {
      if (request.getRequestURI().startsWith("/api/")
          && !SAFE_METHODS.contains(request.getMethod())
          && !CSRF_VALUE.equals(request.getHeader(CSRF_HEADER))) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response
            .getWriter()
            .write(
                "{\"status\":403,\"code\":\"MISSING_CSRF_HEADER\","
                    + "\"detail\":\"Requests that change state need X-Requested-With: SafeLedger\"}");
        return;
      }
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
