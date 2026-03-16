package com.sportsbook.risk.limit;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.counter.LimitType;
import java.util.Optional;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link LimitOverrideStore}. One hash per subject; the hash field encodes {@code
 * {limitType}:{currency}} so a single HGET returns the override directly. Values are stored as
 * decimal strings of {@code long} minor units to stay consistent with how the sliding-window
 * counter encodes amounts.
 */
@Component
public class RedisLimitOverrideStore implements LimitOverrideStore {

  private static final String USER_KEY_PREFIX = "limit:override:user:";
  private static final String MARKET_KEY_PREFIX = "limit:override:market:";

  private final HashOperations<String, String, String> hashOps;

  public RedisLimitOverrideStore(StringRedisTemplate redis) {
    this.hashOps = redis.opsForHash();
  }

  @Override
  public Optional<Long> findUserOverride(String userId, LimitType type, Currency currency) {
    return read(userKey(userId), field(type, currency));
  }

  @Override
  public Optional<Long> findMarketOverride(String marketId, LimitType type, Currency currency) {
    return read(marketKey(marketId), field(type, currency));
  }

  @Override
  public void setUserOverride(String userId, LimitType type, Currency currency, long amount) {
    write(userKey(userId), field(type, currency), amount);
  }

  @Override
  public void setMarketOverride(String marketId, LimitType type, Currency currency, long amount) {
    write(marketKey(marketId), field(type, currency), amount);
  }

  @Override
  public void clearUserOverride(String userId, LimitType type, Currency currency) {
    hashOps.delete(userKey(userId), field(type, currency));
  }

  @Override
  public void clearMarketOverride(String marketId, LimitType type, Currency currency) {
    hashOps.delete(marketKey(marketId), field(type, currency));
  }

  private Optional<Long> read(String key, String field) {
    String raw = hashOps.get(key, field);
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(raw));
    } catch (NumberFormatException e) {
      throw new IllegalStateException(
          "Limit override at " + key + "[" + field + "] is not a long: '" + raw + "'", e);
    }
  }

  private void write(String key, String field, long amount) {
    if (amount < 0) {
      throw new IllegalArgumentException(
          "Limit override must be non-negative, got " + amount + " for " + key + "[" + field + "]");
    }
    hashOps.put(key, field, Long.toString(amount));
  }

  private static String userKey(String userId) {
    return USER_KEY_PREFIX + userId;
  }

  private static String marketKey(String marketId) {
    return MARKET_KEY_PREFIX + marketId;
  }

  private static String field(LimitType type, Currency currency) {
    return type.name() + ":" + currency.name();
  }
}
