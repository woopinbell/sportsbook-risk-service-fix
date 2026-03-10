package com.sportsbook.risk.policy;

/**
 * Outcome attached to a matched pattern rule. Drives the {@code patternsFlagged} field in the
 * {@code /internal/v1/risk/check} response and the routing of the published {@code
 * risk.pattern.suspected} event.
 *
 * <ul>
 *   <li>{@link #SUSPECT} — flag but allow the bet through; analysts review asynchronously.
 *   <li>{@link #REVIEW} — flag and queue for synchronous operator review (admin-api UI).
 *   <li>{@link #BLOCK} — reject the bet immediately, same critical-path response as a limit breach.
 * </ul>
 */
public enum PatternAction {
  SUSPECT,
  REVIEW,
  BLOCK
}
