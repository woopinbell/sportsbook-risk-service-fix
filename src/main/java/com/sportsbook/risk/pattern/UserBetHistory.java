package com.sportsbook.risk.pattern;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Read-only view of recent per-user bet activity that {@link PatternRule} implementations consult.
 * The interface stays narrow so each rule can declare only what it needs and so the production
 * implementation (Redis-backed, built in a later commit) can be swapped for an in-memory test
 * double here.
 *
 * <p>{@link com.sportsbook.risk.counter.SlidingWindowCounter} stores enough information to answer
 * the count / stake queries below — {@code countBetsBetween} maps onto a windowed COUNT lookup,
 * {@code recentStakeAmounts} replays the encoded amounts inside the day-window sorted set, and
 * {@code countSelectionBets} reuses the same sorted-set pattern keyed on selection ID.
 */
public interface UserBetHistory {

  /** Number of bets the user has placed in {@code [from, to)}. Used by the rapid-betting rule. */
  long countBetsBetween(String userId, Instant from, Instant to);

  /**
   * Stake amounts (long minor units) of the most recent {@code lookback} bets for the user, oldest
   * first. May return fewer than {@code lookback} entries when the user is new. Used by the sudden-
   * stake-increase rule.
   */
  List<Long> recentStakeAmounts(String userId, int lookback);

  /**
   * Number of times the user has bet on the same {@code selectionId} within the last {@code window}
   * ending at {@code now}. Used by the repeated-same-selection rule.
   */
  long countSelectionBets(String userId, String selectionId, Duration window, Instant now);
}
