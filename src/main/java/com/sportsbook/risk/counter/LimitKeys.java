package com.sportsbook.risk.counter;

import java.util.List;

/**
 * Single source of truth for the Redis key shape used by the risk-service counters. Every other
 * collaborator (the Kafka consumer that records bets, the check API, the limit override store)
 * builds keys through this class so a change in convention only touches one file.
 *
 * <p>Layout:
 *
 * <pre>
 *   limit:user:{userId}:{limitSuffix}        — sorted set, score=ms, member="{betId}|{amount}"
 *   limit:user:{userId}:{limitSuffix}:sum    — long counter, kept in lockstep by the Lua script
 *   limit:market:{marketId}:{limitSuffix}    — same shape, market-scoped
 * </pre>
 */
public final class LimitKeys {

  private static final String USER_PREFIX = "limit:user:";
  private static final String MARKET_PREFIX = "limit:market:";
  private static final String SUM_SUFFIX = ":sum";

  private LimitKeys() {}

  public static String userKey(String userId, LimitType type) {
    return USER_PREFIX + userId + ":" + type.suffix();
  }

  public static String marketKey(String marketId, LimitType type) {
    return MARKET_PREFIX + marketId + ":" + type.suffix();
  }

  public static String sumKey(String slidingKey) {
    return slidingKey + SUM_SUFFIX;
  }

  public static List<String> userKeyPair(String userId, LimitType type) {
    String slidingKey = userKey(userId, type);
    return List.of(slidingKey, sumKey(slidingKey));
  }

  /** Encodes the per-member token the Lua script decodes back when an entry expires. */
  public static String encodeMember(String betId, long amount) {
    return betId + "|" + amount;
  }
}
