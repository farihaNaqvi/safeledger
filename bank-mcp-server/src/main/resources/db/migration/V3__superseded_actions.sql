-- A newer transfer request in the same session replaces an older pending one, so a Confirm from a
-- stale card or tab cannot run the request the customer has since corrected.
ALTER TABLE pending_action DROP CONSTRAINT pending_action_status_check;
ALTER TABLE pending_action ADD CONSTRAINT pending_action_status_check
    CHECK (status IN ('PENDING', 'CONFIRMED', 'EXECUTED', 'FAILED', 'EXPIRED', 'CANCELLED',
                      'SUPERSEDED'));
