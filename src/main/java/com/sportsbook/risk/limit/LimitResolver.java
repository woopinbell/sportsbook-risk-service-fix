package com.sportsbook.risk.limit;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.policy.RiskLimitProperties;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reads the effective limit for a user or market by composing {@link LimitOverrideStore} on top of
 * {@link RiskLimitProperties} defaults. Lookup order:
 *
 * <ol>
 *   <li>Per-subject override (if the operator has set one via {@code admin-api}).
 *   <li>Policy default from {@code application.yml}.
 * </ol>
 *
 * <p>Market-scoped limits intentionally have no global default in V1 — markets are unlimited unless
 * the operator explicitly sets a cap, which is why {@link #resolveMarket(String, LimitType,
 * Currency)} returns an {@link Optional}.
 */
@Component
public class LimitResolver {

  private final LimitOverrideStore overrides;
  private final RiskLimitProperties policy;

  public LimitResolver(LimitOverrideStore overrides, RiskLimitProperties policy) {
    this.overrides = overrides;
    this.policy = policy;
  }

  public long resolveUser(String userId, LimitType type, Currency currency) {
    return overrides
        .findUserOverride(userId, type, currency)
        .orElseGet(() -> policyDefault(type, currency));
  }

  public Optional<Long> resolveMarket(String marketId, LimitType type, Currency currency) {
    return overrides.findMarketOverride(marketId, type, currency);
  }

  private long policyDefault(LimitType type, Currency currency) {
    return switch (type) {
      case STAKE_DAILY -> policy.stakeDaily(currency);
      case STAKE_WEEKLY -> policy.stakeWeekly(currency);
      case STAKE_MONTHLY -> policy.stakeMonthly(currency);
      case SELECTIONS_PER_MINUTE -> policy.selectionsPerMinute();
    };
  }
}
