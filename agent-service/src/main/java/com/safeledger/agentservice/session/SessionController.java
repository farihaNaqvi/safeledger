package com.safeledger.agentservice.session;

import com.safeledger.agentservice.common.ApiException;
import com.safeledger.agentservice.config.AgentProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo login: pick one of the seeded synthetic customers. Intentionally simple; real authentication
 * is out of scope. What matters is that from here on the customer comes only from the session.
 */
@RestController
public class SessionController {

  public record LoginRequest(@NotNull UUID customerId) {}

  public record SessionView(UUID customerId, String customerName) {}

  private final List<AgentProperties.DemoCustomer> customers;

  public SessionController(AgentProperties properties) {
    this.customers = properties.customers();
  }

  @GetMapping("/api/customers")
  public List<AgentProperties.DemoCustomer> demoCustomers() {
    return customers;
  }

  @PostMapping("/api/session")
  public SessionView login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
    AgentProperties.DemoCustomer customer =
        customers.stream()
            .filter(c -> c.id().equals(body.customerId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "UNKNOWN_CUSTOMER",
                        "Pick one of the demo customers"));
    HttpSession old = request.getSession(false);
    if (old != null) {
      old.invalidate();
    }
    HttpSession session = request.getSession(true);
    session.setAttribute(
        CustomerSession.ATTRIBUTE,
        new CustomerSession(customer.id(), customer.name(), "web-" + UUID.randomUUID()));
    return new SessionView(customer.id(), customer.name());
  }

  @GetMapping("/api/session")
  public SessionView current(HttpServletRequest request) {
    CustomerSession session = CustomerSession.require(request.getSession(false));
    return new SessionView(session.customerId(), session.customerName());
  }

  @DeleteMapping("/api/session")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }
}
