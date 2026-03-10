package com.sportsbook.risk.policy;

import com.sportsbook.protocol.value.Currency;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Default risk limits (ADR-0009). Operators tune values via {@code application.yml}; per-user and
 * per-market overrides live in Redis hashes and take precedence at lookup time. The compact
 * constructor fills in safe fallbacks so a missing yaml key never causes a null dereference at
 * runtime — the service still starts and applies a sane policy.
 *
 * <p>Currency maps are normalised to {@link EnumMap} to give O(1) lookup and a defensive copy so
 * yaml-supplied immutable maps cannot be aliased into runtime mutation. Each amount is a {@code
 * long} minor unit (KRW won, USD cent) consistent with {@code shared-protocol}'s {@code Money}.
 */
@ConfigurationProperties(prefix = "risk.limits")
public record RiskLimitProperties(
    Map<Currency, Long> stakeDaily,
    Map<Currency, Long> stakeWeekly,
    Map<Currency, Long> stakeMonthly,
    Map<Currency, Long> singleBetMax,
    Map<Currency, Long> openExposure,
    int selectionsPerMinute) {

  private static final long DEFAULT_KRW_DAILY = 1_000_000L;
  private static final long DEFAULT_USD_DAILY = 1_000L;
  private static final long DEFAULT_KRW_WEEKLY = 5_000_000L;
  private static final long DEFAULT_USD_WEEKLY = 5_000L;
  private static final long DEFAULT_KRW_MONTHLY = 20_000_000L;
  private static final long DEFAULT_USD_MONTHLY = 20_000L;
  private static final long DEFAULT_KRW_SINGLE = 500_000L;
  private static final long DEFAULT_USD_SINGLE = 500L;
  private static final long DEFAULT_KRW_EXPOSURE = 2_000_000L;
  private static final long DEFAULT_USD_EXPOSURE = 2_000L;
  private static final int DEFAULT_SELECTIONS_PER_MINUTE = 30;

  public RiskLimitProperties {
    stakeDaily = normalised(stakeDaily, DEFAULT_KRW_DAILY, DEFAULT_USD_DAILY);
    stakeWeekly = normalised(stakeWeekly, DEFAULT_KRW_WEEKLY, DEFAULT_USD_WEEKLY);
    stakeMonthly = normalised(stakeMonthly, DEFAULT_KRW_MONTHLY, DEFAULT_USD_MONTHLY);
    singleBetMax = normalised(singleBetMax, DEFAULT_KRW_SINGLE, DEFAULT_USD_SINGLE);
    openExposure = normalised(openExposure, DEFAULT_KRW_EXPOSURE, DEFAULT_USD_EXPOSURE);
    if (selectionsPerMinute <= 0) {
      selectionsPerMinute = DEFAULT_SELECTIONS_PER_MINUTE;
    }
  }

  public long stakeDaily(Currency currency) {
    return require(stakeDaily, currency, "stake-daily");
  }

  public long stakeWeekly(Currency currency) {
    return require(stakeWeekly, currency, "stake-weekly");
  }

  public long stakeMonthly(Currency currency) {
    return require(stakeMonthly, currency, "stake-monthly");
  }

  public long singleBetMax(Currency currency) {
    return require(singleBetMax, currency, "single-bet-max");
  }

  public long openExposure(Currency currency) {
    return require(openExposure, currency, "open-exposure");
  }

  private static Map<Currency, Long> normalised(
      Map<Currency, Long> source, long defaultKrw, long defaultUsd) {
    EnumMap<Currency, Long> copy = new EnumMap<>(Currency.class);
    if (source == null || source.isEmpty()) {
      copy.put(Currency.KRW, defaultKrw);
      copy.put(Currency.USD, defaultUsd);
      return Map.copyOf(copy);
    }
    source.forEach(
        (currency, amount) -> {
          if (amount == null || amount < 0) {
            throw new IllegalArgumentException(
                "Risk limit for " + currency + " must be non-negative, got " + amount);
          }
          copy.put(currency, amount);
        });
    copy.putIfAbsent(Currency.KRW, defaultKrw);
    copy.putIfAbsent(Currency.USD, defaultUsd);
    return Map.copyOf(copy);
  }

  private static long require(Map<Currency, Long> map, Currency currency, String limitName) {
    Long value = map.get(currency);
    if (value == null) {
      throw new IllegalStateException(
          "No " + limitName + " limit configured for currency " + currency);
    }
    return value;
  }
}
