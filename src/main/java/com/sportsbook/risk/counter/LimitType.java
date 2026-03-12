package com.sportsbook.risk.counter;

import java.time.Duration;

/**
 * Risk limit category that the {@link SlidingWindowCounter} understands. Each entry binds a stable
 * key suffix (used to build the Redis sorted-set key) to the sliding window length. Limits that are
 * not time-windowed in the technical sense — {@code SINGLE_BET_MAX} (per-event threshold) and
 * {@code OPEN_EXPOSURE} (live state) — live elsewhere and are intentionally absent here.
 *
 * <p>The {@code measuredIn} hint is informational: SUM-typed limits encode an amount per member
 * ({@code "{betId}|{amount}"}) so the cleanup step can decrement the running sum exactly; COUNT-
 * typed limits use the same encoding with {@code amount=1}, which keeps a single Lua script in
 * play.
 */
public enum LimitType {
  STAKE_DAILY("stake-daily", Duration.ofDays(1), Measure.SUM),
  STAKE_WEEKLY("stake-weekly", Duration.ofDays(7), Measure.SUM),
  STAKE_MONTHLY("stake-monthly", Duration.ofDays(30), Measure.SUM),
  SELECTIONS_PER_MINUTE("selections-per-minute", Duration.ofMinutes(1), Measure.COUNT);

  private final String suffix;
  private final Duration window;
  private final Measure measure;

  LimitType(String suffix, Duration window, Measure measure) {
    this.suffix = suffix;
    this.window = window;
    this.measure = measure;
  }

  public String suffix() {
    return suffix;
  }

  public Duration window() {
    return window;
  }

  public Measure measure() {
    return measure;
  }

  public enum Measure {
    SUM,
    COUNT
  }
}
