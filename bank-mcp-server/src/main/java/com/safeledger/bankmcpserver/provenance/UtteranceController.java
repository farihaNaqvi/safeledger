package com.safeledger.bankmcpserver.provenance;

import com.safeledger.bankmcpserver.identity.CallerIdentity;
import com.safeledger.bankmcpserver.identity.IdentityFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * agent-service posts every customer message here before the model sees it. Like confirm and
 * cancel, this is an internal HTTP endpoint and not an MCP tool, so the model cannot add words to
 * the customer's mouth.
 */
@RestController
public class UtteranceController {

  public record Utterance(String text) {}

  static final int MAX_LENGTH = 2000;

  private final CustomerUtterances utterances;

  public UtteranceController(CustomerUtterances utterances) {
    this.utterances = utterances;
  }

  @PostMapping("/internal/utterances")
  public ResponseEntity<Void> record(
      @RequestBody Utterance body,
      @RequestAttribute(IdentityFilter.ATTRIBUTE) CallerIdentity caller) {
    if (body == null
        || body.text() == null
        || body.text().isBlank()
        || body.text().length() > MAX_LENGTH) {
      return ResponseEntity.badRequest().build();
    }
    utterances.record(caller, body.text());
    return ResponseEntity.noContent().build();
  }
}
