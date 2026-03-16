package com.sportsbook.risk.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.policy.RiskLimitProperties;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LimitResolverTest {

  private static final String USER_ID = "u-1";
  private static final String MARKET_ID = "m-1";
  private static final long POLICY_DAILY_KRW = 1_000_000L;
  private static final long POLICY_DAILY_USD = 1_000L;
  private static final long OVERRIDE_DAILY_KRW = 5_000_000L;
  private static final int POLICY_SELECTIONS = 30;

  @Mock private LimitOverrideStore overrides;
  private RiskLimitProperties policy;
  private LimitResolver resolver;

  @BeforeEach
  void setUp() {
    policy =
        new RiskLimitProperties(
            Map.of(Currency.KRW, POLICY_DAILY_KRW, Currency.USD, POLICY_DAILY_USD),
            null,
            null,
            null,
            null,
            POLICY_SELECTIONS);
    resolver = new LimitResolver(overrides, policy);
  }

  @Test
  void userOverrideWinsOverPolicy() {
    when(overrides.findUserOverride(USER_ID, LimitType.STAKE_DAILY, Currency.KRW))
        .thenReturn(Optional.of(OVERRIDE_DAILY_KRW));

    long limit = resolver.resolveUser(USER_ID, LimitType.STAKE_DAILY, Currency.KRW);

    assertThat(limit).isEqualTo(OVERRIDE_DAILY_KRW);
  }

  @Test
  void userFallsBackToPolicyWhenNoOverride() {
    when(overrides.findUserOverride(USER_ID, LimitType.STAKE_DAILY, Currency.KRW))
        .thenReturn(Optional.empty());

    long limit = resolver.resolveUser(USER_ID, LimitType.STAKE_DAILY, Currency.KRW);

    assertThat(limit).isEqualTo(POLICY_DAILY_KRW);
  }

  @Test
  void selectionsPerMinuteIgnoresCurrency() {
    when(overrides.findUserOverride(USER_ID, LimitType.SELECTIONS_PER_MINUTE, Currency.KRW))
        .thenReturn(Optional.empty());

    long limit = resolver.resolveUser(USER_ID, LimitType.SELECTIONS_PER_MINUTE, Currency.KRW);

    assertThat(limit).isEqualTo(POLICY_SELECTIONS);
  }

  @Test
  void marketReturnsOverrideWhenSet() {
    when(overrides.findMarketOverride(MARKET_ID, LimitType.STAKE_DAILY, Currency.KRW))
        .thenReturn(Optional.of(OVERRIDE_DAILY_KRW));

    Optional<Long> limit = resolver.resolveMarket(MARKET_ID, LimitType.STAKE_DAILY, Currency.KRW);

    assertThat(limit).contains(OVERRIDE_DAILY_KRW);
  }

  @Test
  void marketIsUnlimitedByDefault() {
    when(overrides.findMarketOverride(MARKET_ID, LimitType.STAKE_DAILY, Currency.KRW))
        .thenReturn(Optional.empty());

    Optional<Long> limit = resolver.resolveMarket(MARKET_ID, LimitType.STAKE_DAILY, Currency.KRW);

    assertThat(limit).isEmpty();
  }
}
