package com.sportsbook.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.counter.SlidingWindowCounter;
import com.sportsbook.risk.event.RiskEventPublisher;
import com.sportsbook.risk.limit.LimitResolver;
import com.sportsbook.risk.pattern.PatternContext;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.pattern.RuleEngine;
import com.sportsbook.risk.policy.PatternAction;
import com.sportsbook.risk.policy.RiskLimitProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskCheckServiceTest {

  private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");
  private static final String USER = "u-1";
  private static final String BET = "b-1";

  @Mock private LimitResolver limitResolver;
  @Mock private SlidingWindowCounter counter;
  @Mock private RuleEngine ruleEngine;
  @Mock private RiskEventPublisher publisher;

  private RiskCheckService service;
  private RiskLimitProperties policy;

  @BeforeEach
  void setUp() {
    // Generous policy defaults — tests override per-limit via the resolver mock.
    policy =
        new RiskLimitProperties(
            Map.of(Currency.KRW, 1_000_000L), // stake-daily
            Map.of(Currency.KRW, 5_000_000L), // stake-weekly
            Map.of(Currency.KRW, 20_000_000L), // stake-monthly
            Map.of(Currency.KRW, 500_000L), // single-bet-max
            Map.of(Currency.KRW, 2_000_000L), // open-exposure (unused)
            30);
    service =
        new RiskCheckService(
            policy, limitResolver, counter, ruleEngine, publisher, new SimpleMeterRegistry());
  }

  @Test
  void approvesWhenAllLimitsClearAndNoRulesFire() {
    primeAllLimitsClear();
    when(ruleEngine.evaluate(any(PatternContext.class))).thenReturn(List.of());

    RiskCheckOutcome outcome = service.check(commandWithStake(10_000L));

    assertThat(outcome.approved()).isTrue();
    assertThat(outcome.rejection()).isEmpty();
    assertThat(outcome.patternsFlagged()).isEmpty();
  }

  @Test
  void rejectsOnSingleBetMaxBeforeQueryingRedis() {
    // 600_000 > policy single-bet-max 500_000.
    RiskCheckOutcome outcome = service.check(commandWithStake(600_000L));

    assertThat(outcome.approved()).isFalse();
    assertThat(outcome.rejection()).isPresent();
    assertThat(outcome.rejection().get().reason()).isEqualTo("SINGLE_BET_MAX_EXCEEDED");
  }

  @Test
  void rejectsOnStakeDailyExceeded() {
    when(counter.currentSum(anyString(), eq(LimitType.STAKE_DAILY.window()), any(Instant.class)))
        .thenReturn(950_000L);
    when(counter.currentSum(anyString(), eq(LimitType.STAKE_WEEKLY.window()), any(Instant.class)))
        .thenReturn(0L);
    when(counter.currentSum(anyString(), eq(LimitType.STAKE_MONTHLY.window()), any(Instant.class)))
        .thenReturn(0L);
    when(counter.currentSum(
            anyString(), eq(LimitType.SELECTIONS_PER_MINUTE.window()), any(Instant.class)))
        .thenReturn(0L);
    when(limitResolver.resolveUser(eq(USER), eq(LimitType.STAKE_DAILY), eq(Currency.KRW)))
        .thenReturn(1_000_000L);
    when(limitResolver.resolveUser(eq(USER), eq(LimitType.STAKE_WEEKLY), eq(Currency.KRW)))
        .thenReturn(5_000_000L);
    when(limitResolver.resolveUser(eq(USER), eq(LimitType.STAKE_MONTHLY), eq(Currency.KRW)))
        .thenReturn(20_000_000L);
    when(limitResolver.resolveUser(
            eq(USER), eq(LimitType.SELECTIONS_PER_MINUTE), any(Currency.class)))
        .thenReturn(30L);
    when(ruleEngine.evaluate(any(PatternContext.class))).thenReturn(List.of());

    // 950_000 already used + 100_000 candidate > 1_000_000 limit.
    RiskCheckOutcome outcome = service.check(commandWithStake(100_000L));

    assertThat(outcome.approved()).isFalse();
    LimitRejection rej = outcome.rejection().orElseThrow();
    assertThat(rej.reason()).isEqualTo("STAKE_DAILY_LIMIT_EXCEEDED");
    assertThat(rej.current()).isEqualTo(950_000L);
    assertThat(rej.limit()).isEqualTo(1_000_000L);
    assertThat(rej.requested()).isEqualTo(100_000L);
  }

  @Test
  void rejectsOnSelectionsPerMinuteExceeded() {
    primeAllLimitsClear();
    when(counter.currentSum(
            anyString(), eq(LimitType.SELECTIONS_PER_MINUTE.window()), any(Instant.class)))
        .thenReturn(29L);
    when(limitResolver.resolveUser(
            eq(USER), eq(LimitType.SELECTIONS_PER_MINUTE), any(Currency.class)))
        .thenReturn(30L);
    when(ruleEngine.evaluate(any(PatternContext.class))).thenReturn(List.of());

    // 29 prior + 2 candidate selections > 30 limit.
    RiskCheckCommand cmd =
        new RiskCheckCommand(USER, BET, Money.krw(10_000L), List.of("s-1", "s-2"), NOW);

    RiskCheckOutcome outcome = service.check(cmd);

    assertThat(outcome.approved()).isFalse();
    assertThat(outcome.rejection().orElseThrow().reason())
        .isEqualTo("SELECTIONS_PER_MINUTE_LIMIT_EXCEEDED");
  }

  @Test
  void rejectsOnBlockPatternMatch() {
    primeAllLimitsClear();
    PatternMatch block = new PatternMatch("rapid-betting", PatternAction.BLOCK, "30 reached");
    when(ruleEngine.evaluate(any(PatternContext.class))).thenReturn(List.of(block));

    RiskCheckOutcome outcome = service.check(commandWithStake(10_000L));

    assertThat(outcome.approved()).isFalse();
    assertThat(outcome.rejection().orElseThrow().reason()).isEqualTo("PATTERN_RAPID_BETTING");
    assertThat(outcome.patternsFlagged()).containsExactly(block);
  }

  @Test
  void approvesButFlagsOnSuspectPattern() {
    primeAllLimitsClear();
    PatternMatch suspect = new PatternMatch("rapid-betting", PatternAction.SUSPECT, "flagged");
    when(ruleEngine.evaluate(any(PatternContext.class))).thenReturn(List.of(suspect));

    RiskCheckOutcome outcome = service.check(commandWithStake(10_000L));

    assertThat(outcome.approved()).isTrue();
    assertThat(outcome.patternsFlagged()).containsExactly(suspect);
  }

  private void primeAllLimitsClear() {
    when(counter.currentSum(anyString(), any(Duration.class), any(Instant.class))).thenReturn(0L);
    when(limitResolver.resolveUser(anyString(), any(LimitType.class), any(Currency.class)))
        .thenReturn(Long.MAX_VALUE / 2);
  }

  private RiskCheckCommand commandWithStake(long amount) {
    return new RiskCheckCommand(USER, BET, Money.krw(amount), List.of("s-1"), NOW);
  }
}
