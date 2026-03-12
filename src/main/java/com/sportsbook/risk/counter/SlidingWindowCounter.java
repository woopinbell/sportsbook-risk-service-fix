package com.sportsbook.risk.counter;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Atomic sliding-window counter backed by a Redis sorted set + sum key pair. Both writes (a new bet
 * recorded after consuming {@code bet.placed}) and reads (the check API peeking at the current sum)
 * execute the same Lua script so the expired-entry cleanup is part of every operation.
 *
 * <p>The script lives at {@code scripts/sliding-window.lua}. It returns the post-cleanup current
 * sum as a {@code Long}, which is also the post-record sum for write calls.
 */
@Component
public class SlidingWindowCounter {

  private static final Duration TTL_SAFETY_MULTIPLIER = Duration.ZERO;
  private static final long TTL_MULTIPLIER = 2L;
  private static final long MIN_TTL_SECONDS = 60L;
  private static final String READ_ONLY_MEMBER = "";
  private static final long READ_ONLY_AMOUNT = 0L;

  private final StringRedisTemplate redis;
  private final Clock clock;
  private final RedisScript<Long> script;

  public SlidingWindowCounter(StringRedisTemplate redis, Clock clock) {
    this.redis = redis;
    this.clock = clock;
    this.script = new DefaultRedisScript<>(loadScript(), Long.class);
  }

  /**
   * Records the bet against {@code slidingKey + sumKey} and returns the resulting windowed sum.
   * Callers compose {@code slidingKey} via {@link LimitKeys}. {@code memberToken} is typically
   * {@link LimitKeys#encodeMember(String, long)}; the Lua script decodes the trailing amount when
   * an entry ages out.
   */
  public long record(
      String slidingKey, String memberToken, long amount, Duration window, Instant now) {
    if (amount <= 0) {
      throw new IllegalArgumentException("amount must be positive for record(), got " + amount);
    }
    return execute(slidingKey, memberToken, amount, window, now);
  }

  /** Convenience overload that uses the injected {@link Clock}. */
  public long record(String slidingKey, String memberToken, long amount, Duration window) {
    return record(slidingKey, memberToken, amount, window, clock.instant());
  }

  /**
   * Returns the current windowed sum without recording anything. Still runs the cleanup pass so the
   * read is consistent with what a record() call would see.
   */
  public long currentSum(String slidingKey, Duration window, Instant now) {
    return execute(slidingKey, READ_ONLY_MEMBER, READ_ONLY_AMOUNT, window, now);
  }

  public long currentSum(String slidingKey, Duration window) {
    return currentSum(slidingKey, window, clock.instant());
  }

  private long execute(
      String slidingKey, String memberToken, long amount, Duration window, Instant now) {
    List<String> keys = List.of(slidingKey, LimitKeys.sumKey(slidingKey));
    long ttlSeconds = ttlSecondsFor(window);
    Long result =
        redis.execute(
            script,
            keys,
            Long.toString(now.toEpochMilli()),
            Long.toString(window.toMillis()),
            memberToken,
            Long.toString(amount),
            Long.toString(ttlSeconds));
    return result == null ? 0L : result;
  }

  private static long ttlSecondsFor(Duration window) {
    long base =
        Math.max(MIN_TTL_SECONDS, window.plus(TTL_SAFETY_MULTIPLIER).getSeconds() * TTL_MULTIPLIER);
    return base;
  }

  private static String loadScript() {
    try {
      return StreamUtils.copyToString(
          new ClassPathResource("scripts/sliding-window.lua").getInputStream(),
          StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load sliding-window.lua from classpath", e);
    }
  }
}
