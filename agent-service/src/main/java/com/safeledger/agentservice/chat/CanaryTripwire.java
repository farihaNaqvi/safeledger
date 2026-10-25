package com.safeledger.agentservice.chat;

import com.safeledger.agentservice.config.AgentProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Each customer's seed data holds a canary: a reference code that appears nowhere else. If one
 * customer's code shows up in a reply to another customer, data has crossed between customers by
 * some path the other controls missed. The reply is withheld and the event raised, so such a leak
 * is detected by a tripwire rather than by guessing what leaked data looks like.
 */
@Component
public class CanaryTripwire {

  static final String WITHHELD =
      "Sorry, I can't show that reply. I can help with your own accounts, cards, transactions,"
          + " payees and disputes.";

  private static final Logger log = LoggerFactory.getLogger(CanaryTripwire.class);

  private final Map<UUID, List<String>> canaries;
  private final MeterRegistry meters;

  public CanaryTripwire(AgentProperties properties, MeterRegistry meters) {
    this.canaries = properties.canaries();
    this.meters = meters;
  }

  /** True when the text holds a canary that belongs to a customer other than {@code customer}. */
  boolean tripped(UUID customer, String text) {
    if (text == null) {
      return false;
    }
    for (Map.Entry<UUID, List<String>> entry : canaries.entrySet()) {
      if (entry.getKey().equals(customer)) {
        continue;
      }
      for (String canary : entry.getValue()) {
        if (text.contains(canary)) {
          log.error("Security event: another customer's canary in a reply; reply withheld");
          meters.counter("safeledger.canary.tripped").increment();
          return true;
        }
      }
    }
    return false;
  }
}
