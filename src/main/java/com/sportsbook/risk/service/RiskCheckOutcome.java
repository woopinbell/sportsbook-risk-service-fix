package com.sportsbook.risk.service;

import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.policy.PatternAction;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of {@link RiskCheckService#check}. The check API maps this directly to its response DTO;
 * the Kafka consumer side maps the limit / pattern fields to {@code risk.limit.violated} and {@code
 * risk.pattern.suspected} Avro events.
 *
 * @param approved {@code false} when a limit was exceeded or a BLOCK pattern fired
 * @param rejection breakdown of the first limit that tripped, absent when no limit tripped
 * @param patternsFlagged every rule that fired — SUSPECT / REVIEW alongside any BLOCK
 */
public record RiskCheckOutcome(
    boolean approved, Optional<LimitRejection> rejection, List<PatternMatch> patternsFlagged) {

  public RiskCheckOutcome {
    Objects.requireNonNull(rejection, "rejection");
    Objects.requireNonNull(patternsFlagged, "patternsFlagged");
    patternsFlagged = List.copyOf(patternsFlagged);
  }

  public static RiskCheckOutcome approved(List<PatternMatch> patternsFlagged) {
    return new RiskCheckOutcome(true, Optional.empty(), patternsFlagged);
  }

  public static RiskCheckOutcome rejectedByLimit(
      LimitRejection rejection, List<PatternMatch> matches) {
    return new RiskCheckOutcome(false, Optional.of(rejection), matches);
  }

  public static RiskCheckOutcome rejectedByPattern(PatternMatch block, List<PatternMatch> matches) {
    LimitRejection synthetic =
        new LimitRejection(
            "PATTERN_" + block.ruleName().replace('-', '_').toUpperCase(),
            null,
            0L,
            0L,
            0L,
            PatternAction.BLOCK.name());
    return new RiskCheckOutcome(false, Optional.of(synthetic), matches);
  }
}
