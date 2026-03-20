package com.sportsbook.risk.pattern;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

/**
 * Redis-backed implementation of {@link UserBetHistory} and {@link UserBetHistoryWriter}.
 *
 * <p>Two sorted sets per user:
 *
 * <pre>
 *   history:user:{userId}:bets             score=ms, member="{betId}|{amount}"
 *       Powers countBetsBetween (ZCOUNT score range) and recentStakeAmounts
 *       (ZRANGE reverse with limit, then parse trailing amount).
 *
 *   history:user:{userId}:sel:{selectionId} score=ms, member=betId
 *       Powers countSelectionBets via a ZCOUNT against [now - window, now].
 * </pre>
 *
 * <p>Both keys carry the same TTL — currently 7 days — which comfortably covers every V1 pattern
 * window (rapid-betting 60s, sudden-stake-increase ~tens of bets back, repeated-same-selection up
 * to 24h). Cleanup is lazy: TTL expires whole keys for inactive users, and ZRANGE / ZCOUNT skip
 * out-of-range entries cheaply for active ones. We intentionally do not run a separate trim because
 * the pattern rules' answers are exact for the windows they care about regardless of older entries
 * lingering in the set.
 */
@Component
public class RedisUserBetHistory implements UserBetHistory, UserBetHistoryWriter {

  private static final String BETS_KEY_PREFIX = "history:user:";
  private static final String BETS_KEY_SUFFIX = ":bets";
  private static final String SELECTION_KEY_INFIX = ":sel:";
  private static final Duration TTL = Duration.ofDays(7);
  private static final char AMOUNT_DELIMITER = '|';

  private final StringRedisTemplate redis;
  private final ZSetOperations<String, String> zset;

  public RedisUserBetHistory(StringRedisTemplate redis) {
    this.redis = redis;
    this.zset = redis.opsForZSet();
  }

  @Override
  public void recordBet(
      String userId, String betId, long stakeAmount, List<String> selectionIds, Instant now) {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(betId, "betId");
    Objects.requireNonNull(selectionIds, "selectionIds");
    Objects.requireNonNull(now, "now");
    if (stakeAmount < 0) {
      throw new IllegalArgumentException("stakeAmount must be non-negative, got " + stakeAmount);
    }
    double score = (double) now.toEpochMilli();
    String betsKey = betsKey(userId);
    zset.add(betsKey, encodeBetMember(betId, stakeAmount), score);
    redis.expire(betsKey, TTL);
    for (String selectionId : selectionIds) {
      String selectionKey = selectionKey(userId, selectionId);
      zset.add(selectionKey, betId, score);
      redis.expire(selectionKey, TTL);
    }
  }

  @Override
  public long countBetsBetween(String userId, Instant from, Instant to) {
    Long count = zset.count(betsKey(userId), from.toEpochMilli(), to.toEpochMilli());
    return count == null ? 0L : count;
  }

  @Override
  public List<Long> recentStakeAmounts(String userId, int lookback) {
    if (lookback <= 0) {
      return List.of();
    }
    Set<String> members = zset.reverseRange(betsKey(userId), 0, (long) lookback - 1);
    if (members == null || members.isEmpty()) {
      return List.of();
    }
    List<Long> amounts = new ArrayList<>(members.size());
    for (String member : members) {
      int pipe = member.lastIndexOf(AMOUNT_DELIMITER);
      if (pipe < 0) {
        continue;
      }
      try {
        amounts.add(Long.parseLong(member.substring(pipe + 1)));
      } catch (NumberFormatException ignored) {
        // History writer is the only producer of this key, so a malformed amount means data
        // corruption — skip rather than poison the rule with bogus arithmetic.
      }
    }
    // ZRANGE REV gives newest-first; the contract is oldest-first.
    Collections.reverse(amounts);
    return List.copyOf(amounts);
  }

  @Override
  public long countSelectionBets(String userId, String selectionId, Duration window, Instant now) {
    long min = now.minus(window).toEpochMilli();
    long max = now.toEpochMilli();
    Long count = zset.count(selectionKey(userId, selectionId), min, max);
    return count == null ? 0L : count;
  }

  private static String betsKey(String userId) {
    return BETS_KEY_PREFIX + userId + BETS_KEY_SUFFIX;
  }

  private static String selectionKey(String userId, String selectionId) {
    return BETS_KEY_PREFIX + userId + SELECTION_KEY_INFIX + selectionId;
  }

  private static String encodeBetMember(String betId, long amount) {
    return betId + AMOUNT_DELIMITER + amount;
  }
}
