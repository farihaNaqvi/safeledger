package com.safeledger.bankmcpserver.action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Marks pending actions EXPIRED once their confirmation window has passed. */
@Component
public class ActionExpiryJob {

  private static final Logger log = LoggerFactory.getLogger(ActionExpiryJob.class);

  private final PendingActionService service;

  public ActionExpiryJob(PendingActionService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${safeledger.actions.expiry-sweep-interval}")
  public void sweep() {
    int expired = service.expireOverdue();
    if (expired > 0) {
      log.info("Expired pending actions: count={}", expired);
    }
  }
}
