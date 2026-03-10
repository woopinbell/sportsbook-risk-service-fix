package com.sportsbook.risk.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pattern detection rules (rule-based, V1 ML-free). Each rule is independently toggleable and
 * carries its own thresholds, so an operator can dial the system from "log everything" (SUSPECT all
 * three) through "soft review" to "hard block" without redeploying.
 *
 * <p>Compact constructors enforce strictly positive numerics where a non-positive value would be
 * nonsensical (zero or negative window has no semantic) and fall back to sensible defaults when the
 * yaml entry is missing entirely. Action defaults stay conservative — SUSPECT, never BLOCK — so an
 * undocumented yaml does not silently start rejecting traffic on the betting-service critical path.
 */
@ConfigurationProperties(prefix = "risk.patterns")
public record RiskPatternProperties(
    RapidBetting rapidBetting,
    SuddenStakeIncrease suddenStakeIncrease,
    RepeatedSameSelection repeatedSameSelection) {

  private static final int DEFAULT_RAPID_WINDOW_SECONDS = 60;
  private static final int DEFAULT_RAPID_MAX_BETS = 30;
  private static final int DEFAULT_SUDDEN_MULTIPLIER = 10;
  private static final int DEFAULT_SUDDEN_LOOKBACK = 10;
  private static final int DEFAULT_REPEATED_WINDOW_HOURS = 24;
  private static final int DEFAULT_REPEATED_MAX_COUNT = 5;

  public RiskPatternProperties {
    if (rapidBetting == null) {
      rapidBetting = RapidBetting.disabledDefault();
    }
    if (suddenStakeIncrease == null) {
      suddenStakeIncrease = SuddenStakeIncrease.disabledDefault();
    }
    if (repeatedSameSelection == null) {
      repeatedSameSelection = RepeatedSameSelection.disabledDefault();
    }
  }

  /** More than {@code maxBets} bets in the last {@code windowSeconds}. */
  public record RapidBetting(
      boolean enabled, int windowSeconds, int maxBets, PatternAction action) {

    public RapidBetting {
      if (action == null) {
        action = PatternAction.SUSPECT;
      }
      if (enabled) {
        if (windowSeconds <= 0) {
          throw new IllegalArgumentException(
              "rapid-betting.window-seconds must be positive, got " + windowSeconds);
        }
        if (maxBets <= 0) {
          throw new IllegalArgumentException(
              "rapid-betting.max-bets must be positive, got " + maxBets);
        }
      }
    }

    static RapidBetting disabledDefault() {
      return new RapidBetting(
          false, DEFAULT_RAPID_WINDOW_SECONDS, DEFAULT_RAPID_MAX_BETS, PatternAction.SUSPECT);
    }
  }

  /**
   * Stake on the incoming bet is at least {@code multiplierThreshold} times the median stake over
   * the last {@code lookbackBets} bets.
   */
  public record SuddenStakeIncrease(
      boolean enabled, int multiplierThreshold, int lookbackBets, PatternAction action) {

    public SuddenStakeIncrease {
      if (action == null) {
        action = PatternAction.SUSPECT;
      }
      if (enabled) {
        if (multiplierThreshold <= 1) {
          throw new IllegalArgumentException(
              "sudden-stake-increase.multiplier-threshold must be > 1, got " + multiplierThreshold);
        }
        if (lookbackBets <= 0) {
          throw new IllegalArgumentException(
              "sudden-stake-increase.lookback-bets must be positive, got " + lookbackBets);
        }
      }
    }

    static SuddenStakeIncrease disabledDefault() {
      return new SuddenStakeIncrease(
          false, DEFAULT_SUDDEN_MULTIPLIER, DEFAULT_SUDDEN_LOOKBACK, PatternAction.SUSPECT);
    }
  }

  /**
   * Same {@code SelectionId} re-bet more than {@code maxCount} times within {@code windowHours}.
   */
  public record RepeatedSameSelection(
      boolean enabled, int windowHours, int maxCount, PatternAction action) {

    public RepeatedSameSelection {
      if (action == null) {
        action = PatternAction.REVIEW;
      }
      if (enabled) {
        if (windowHours <= 0) {
          throw new IllegalArgumentException(
              "repeated-same-selection.window-hours must be positive, got " + windowHours);
        }
        if (maxCount <= 0) {
          throw new IllegalArgumentException(
              "repeated-same-selection.max-count must be positive, got " + maxCount);
        }
      }
    }

    static RepeatedSameSelection disabledDefault() {
      return new RepeatedSameSelection(
          false, DEFAULT_REPEATED_WINDOW_HOURS, DEFAULT_REPEATED_MAX_COUNT, PatternAction.REVIEW);
    }
  }
}
