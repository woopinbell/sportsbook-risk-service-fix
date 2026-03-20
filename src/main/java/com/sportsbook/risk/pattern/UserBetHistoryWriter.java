package com.sportsbook.risk.pattern;

import java.time.Instant;
import java.util.List;

/**
 * Write side of the pattern-rule history. The Kafka consumer that listens to {@code bet.placed} is
 * the only call site in production — it event-sources both the runtime sliding-window counters (via
 * {@link com.sportsbook.risk.counter.SlidingWindowCounter}) and the rule history (via this
 * interface) from the same upstream signal.
 */
public interface UserBetHistoryWriter {

  /**
   * Records a single accepted bet's history footprint for the user. The implementation must keep
   * the writes observable through every {@link UserBetHistory} method below, so a downstream rule
   * evaluation against the same user instantly reflects this bet.
   */
  void recordBet(
      String userId, String betId, long stakeAmount, List<String> selectionIds, Instant now);
}
