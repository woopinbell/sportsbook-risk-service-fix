package com.sportsbook.risk.limit;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.counter.LimitType;
import java.util.Optional;

/**
 * Storage for operator-driven limit overrides. The risk-service intentionally has no relational
 * database in V1, so both user-scoped and market-scoped overrides live in Redis hashes — one hash
 * per subject, hash field keyed on {@code limitType:currency}. {@link com.sportsbook.risk.counter
 * .SlidingWindowCounter} keeps the runtime accumulators; this store keeps the per-subject
 * thresholds those accumulators are compared against.
 *
 * <p>The {@code admin-api} mutates this store via the risk-service REST surface; everything else is
 * read-only. The interface stays storage-agnostic so {@code LimitResolver} can compose policy
 * fallback over either the Redis implementation or an in-memory test double without further
 * indirection.
 */
public interface LimitOverrideStore {

  /** Returns the override for a user-scoped limit, or {@link Optional#empty()} if unset. */
  Optional<Long> findUserOverride(String userId, LimitType type, Currency currency);

  /** Returns the override for a market-scoped limit, or {@link Optional#empty()} if unset. */
  Optional<Long> findMarketOverride(String marketId, LimitType type, Currency currency);

  /** Sets or replaces a user-scoped override. Negative {@code amount} is rejected. */
  void setUserOverride(String userId, LimitType type, Currency currency, long amount);

  /** Sets or replaces a market-scoped override. Negative {@code amount} is rejected. */
  void setMarketOverride(String marketId, LimitType type, Currency currency, long amount);

  /** Removes a user-scoped override so the next lookup falls back to policy defaults. */
  void clearUserOverride(String userId, LimitType type, Currency currency);

  /** Removes a market-scoped override. */
  void clearMarketOverride(String marketId, LimitType type, Currency currency);
}
