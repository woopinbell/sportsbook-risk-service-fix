package com.sportsbook.risk.pattern;

import com.sportsbook.protocol.value.Money;
import java.time.Instant;
import java.util.List;

/**
 * Snapshot of everything a {@link PatternRule} needs to look at when evaluating a single bet. The
 * record is intentionally flat and immutable: it is passed by value to every rule and rules must
 * never mutate it.
 *
 * @param userId end-user identifier (raw string, already validated upstream)
 * @param betId the candidate bet under evaluation; rules use it for log / event correlation
 * @param stake stake of the candidate bet in {@link Money}; rules read {@code amount} as the long
 *     minor unit count and ignore currency (limits and pattern thresholds are currency-scoped at
 *     the policy layer)
 * @param selectionIds raw selection identifiers in the candidate slip; used by {@code
 *     repeated-same-selection}
 * @param now evaluation time, supplied explicitly so unit tests can pass a fixed instant without
 *     plumbing a {@link java.time.Clock} into every rule
 */
public record PatternContext(
    String userId, String betId, Money stake, List<String> selectionIds, Instant now) {

  public PatternContext {
    selectionIds = List.copyOf(selectionIds);
  }
}
