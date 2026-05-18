package com.sportsbook.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.event.RiskEventPublisher;
import com.sportsbook.risk.limit.LimitResolver;
import com.sportsbook.risk.pattern.PatternContext;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.pattern.RuleEngine;
import com.sportsbook.risk.policy.PatternAction;
import com.sportsbook.risk.policy.RiskLimitProperties;
import com.sportsbook.risk.snapshot.LimitSnapshot;
import com.sportsbook.risk.snapshot.PatternSnapshot;
import com.sportsbook.risk.snapshot.RiskSnapshot;
import com.sportsbook.risk.snapshot.RiskSnapshotReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
  @Mock private RiskSnapshotReader snapshots;
  @Mock private RiskSnapshot riskSnapshot;
  @Mock private LimitSnapshot limitSnapshot;
  @Mock private PatternSnapshot patternSnapshot;
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
            policy, limitResolver, snapshots, ruleEngine, publisher, new SimpleMeterRegistry());
  }

  @Test
  void approvesWhenAllLimitsClearAndNoRulesFire() {
    primeAllLimitsClear();
    when(ruleEngine.evaluate(any(PatternContext.class), same(patternSnapshot)))
        .thenReturn(List.of());

    RiskCheckOutcome outcome = service.check(commandWithStake(10_000L));

    assertThat(outcome.approved()).isTrue();
    assertThat(outcome.rejection()).isEmpty();
    assertThat(outcome.patternsFlagged()).isEmpty();
    verify(snapshots).read(eq(USER), eq(Currency.KRW), any(PatternContext.class));
  }

  @Test
  void rejectsOnSingleBetMaxBeforeQueryingRedis() {
    // 600_000 > policy single-bet-max 500_000.
    RiskCheckOutcome outcome = service.check(commandWithStake(600_000L));

    assertThat(outcome.approved()).isFalse();
    LimitRejection rejection = outcome.rejection().orElseThrow();
    assertThat(rejection.reason()).isEqualTo("SINGLE_BET_MAX_EXCEEDED");
    assertThat(rejection.currency()).isEqualTo(Currency.KRW);
    assertThat(rejection.current()).isZero();
    assertThat(rejection.limit()).isEqualTo(500_000L);
    assertThat(rejection.requested()).isEqualTo(600_000L);
    assertThat(rejection.action()).isEqualTo(PatternAction.BLOCK.name());
    verify(publisher).publishLimitViolated(USER, rejection, NOW);
    verify(snapshots, never())
        .read(any(String.class), any(Currency.class), any(PatternContext.class));
  }

  @Test
  void rejectsOnStakeDailyExceeded() {
    primeSnapshot();
    when(limitSnapshot.current(LimitType.STAKE_DAILY)).thenReturn(950_000L);
    when(limitResolver.resolveUserFromSnapshot(eq(LimitType.STAKE_DAILY), eq(Currency.KRW), any()))
        .thenReturn(1_000_000L);
    when(limitResolver.resolveUserFromSnapshot(eq(LimitType.STAKE_WEEKLY), eq(Currency.KRW), any()))
        .thenReturn(5_000_000L);
    when(limitResolver.resolveUserFromSnapshot(
            eq(LimitType.STAKE_MONTHLY), eq(Currency.KRW), any()))
        .thenReturn(20_000_000L);
    when(limitResolver.resolveUserFromSnapshot(
            eq(LimitType.SELECTIONS_PER_MINUTE), any(Currency.class), any()))
        .thenReturn(30L);

    // 950_000 already used + 100_000 candidate > 1_000_000 limit.
    RiskCheckOutcome outcome = service.check(commandWithStake(100_000L));

    assertThat(outcome.approved()).isFalse();
    LimitRejection rej = outcome.rejection().orElseThrow();
    assertThat(rej.reason()).isEqualTo("STAKE_DAILY_LIMIT_EXCEEDED");
    assertThat(rej.current()).isEqualTo(950_000L);
    assertThat(rej.limit()).isEqualTo(1_000_000L);
    assertThat(rej.requested()).isEqualTo(100_000L);
    verify(ruleEngine, never()).evaluate(any(PatternContext.class), any(PatternSnapshot.class));
    verify(publisher).publishLimitViolated(eq(USER), eq(rej), eq(NOW));
  }

  @Test
  void rejectsOnSelectionsPerMinuteExceeded() {
    primeAllLimitsClear();
    when(limitSnapshot.current(LimitType.SELECTIONS_PER_MINUTE)).thenReturn(29L);
    when(limitResolver.resolveUserFromSnapshot(
            eq(LimitType.SELECTIONS_PER_MINUTE), any(Currency.class), any()))
        .thenReturn(30L);

    // 29 prior + 2 candidate selections > 30 limit.
    RiskCheckCommand cmd =
        new RiskCheckCommand(USER, BET, Money.krw(10_000L), List.of("s-1", "s-2"), NOW);

    RiskCheckOutcome outcome = service.check(cmd);

    assertThat(outcome.approved()).isFalse();
    LimitRejection rejection = outcome.rejection().orElseThrow();
    assertThat(rejection.reason()).isEqualTo("SELECTIONS_PER_MINUTE_LIMIT_EXCEEDED");
    assertThat(rejection.currency()).isNull();
    assertThat(rejection.current()).isEqualTo(29L);
    assertThat(rejection.limit()).isEqualTo(30L);
    assertThat(rejection.requested()).isEqualTo(2L);
    assertThat(rejection.action()).isEqualTo(PatternAction.BLOCK.name());
    verify(publisher).publishLimitViolated(USER, rejection, NOW);
    verify(ruleEngine, never()).evaluate(any(PatternContext.class), any(PatternSnapshot.class));
  }

  @Test
  void weeklyRejectionPreservesCurrentLimitAndRequestedPayload() {
    primeAllLimitsClear();
    when(limitSnapshot.current(LimitType.STAKE_WEEKLY)).thenReturn(4_900_000L);
    when(limitResolver.resolveUserFromSnapshot(eq(LimitType.STAKE_WEEKLY), eq(Currency.KRW), any()))
        .thenReturn(5_000_000L);

    RiskCheckOutcome outcome = service.check(commandWithStake(200_000L));

    LimitRejection rejection = outcome.rejection().orElseThrow();
    assertThat(rejection.reason()).isEqualTo("STAKE_WEEKLY_LIMIT_EXCEEDED");
    assertThat(rejection.current()).isEqualTo(4_900_000L);
    assertThat(rejection.limit()).isEqualTo(5_000_000L);
    assertThat(rejection.requested()).isEqualTo(200_000L);
    verify(publisher).publishLimitViolated(USER, rejection, NOW);
    verify(ruleEngine, never()).evaluate(any(PatternContext.class), any(PatternSnapshot.class));
  }

  @Test
  void monthlyRejectionPreservesCurrentLimitAndRequestedPayload() {
    primeAllLimitsClear();
    when(limitSnapshot.current(LimitType.STAKE_MONTHLY)).thenReturn(19_900_000L);
    when(limitResolver.resolveUserFromSnapshot(
            eq(LimitType.STAKE_MONTHLY), eq(Currency.KRW), any()))
        .thenReturn(20_000_000L);

    RiskCheckOutcome outcome = service.check(commandWithStake(200_000L));

    LimitRejection rejection = outcome.rejection().orElseThrow();
    assertThat(rejection.reason()).isEqualTo("STAKE_MONTHLY_LIMIT_EXCEEDED");
    assertThat(rejection.current()).isEqualTo(19_900_000L);
    assertThat(rejection.limit()).isEqualTo(20_000_000L);
    assertThat(rejection.requested()).isEqualTo(200_000L);
    verify(publisher).publishLimitViolated(USER, rejection, NOW);
    verify(ruleEngine, never()).evaluate(any(PatternContext.class), any(PatternSnapshot.class));
  }

  @Test
  void rejectsOnBlockPatternMatch() {
    primeAllLimitsClear();
    PatternMatch block = new PatternMatch("rapid-betting", PatternAction.BLOCK, "30 reached");
    when(ruleEngine.evaluate(any(PatternContext.class), same(patternSnapshot)))
        .thenReturn(List.of(block));

    RiskCheckOutcome outcome = service.check(commandWithStake(10_000L));

    assertThat(outcome.approved()).isFalse();
    assertThat(outcome.rejection().orElseThrow().reason()).isEqualTo("PATTERN_RAPID_BETTING");
    assertThat(outcome.patternsFlagged()).containsExactly(block);
    verify(publisher).publishPatternSuspected(USER, block, NOW);
  }

  @Test
  void approvesButFlagsOnSuspectPattern() {
    primeAllLimitsClear();
    PatternMatch suspect = new PatternMatch("rapid-betting", PatternAction.SUSPECT, "flagged");
    when(ruleEngine.evaluate(any(PatternContext.class), same(patternSnapshot)))
        .thenReturn(List.of(suspect));

    RiskCheckOutcome outcome = service.check(commandWithStake(10_000L));

    assertThat(outcome.approved()).isTrue();
    assertThat(outcome.patternsFlagged()).containsExactly(suspect);
    verify(publisher).publishPatternSuspected(USER, suspect, NOW);
  }

  @Test
  void publishesEveryPatternInTheExactRuleEngineOrderBeforeApplyingBlock() {
    primeAllLimitsClear();
    PatternMatch rapid = new PatternMatch("rapid-betting", PatternAction.SUSPECT, "rapid");
    PatternMatch sudden = new PatternMatch("sudden-stake-increase", PatternAction.BLOCK, "sudden");
    PatternMatch repeated =
        new PatternMatch("repeated-same-selection", PatternAction.REVIEW, "repeated");
    when(ruleEngine.evaluate(any(PatternContext.class), same(patternSnapshot)))
        .thenReturn(List.of(rapid, sudden, repeated));

    RiskCheckOutcome outcome = service.check(commandWithStake(10_000L));

    assertThat(outcome.approved()).isFalse();
    assertThat(outcome.rejection().orElseThrow().reason())
        .isEqualTo("PATTERN_SUDDEN_STAKE_INCREASE");
    assertThat(outcome.patternsFlagged()).containsExactly(rapid, sudden, repeated);
    InOrder order = inOrder(publisher);
    order.verify(publisher).publishPatternSuspected(USER, rapid, NOW);
    order.verify(publisher).publishPatternSuspected(USER, sudden, NOW);
    order.verify(publisher).publishPatternSuspected(USER, repeated, NOW);
    verifyNoMoreInteractions(publisher);
  }

  private void primeAllLimitsClear() {
    primeSnapshot();
    when(limitSnapshot.current(any(LimitType.class))).thenReturn(0L);
    when(limitResolver.resolveUserFromSnapshot(any(LimitType.class), any(Currency.class), any()))
        .thenReturn(Long.MAX_VALUE / 2);
  }

  private void primeSnapshot() {
    when(snapshots.read(eq(USER), eq(Currency.KRW), any(PatternContext.class)))
        .thenReturn(riskSnapshot);
    when(riskSnapshot.limits()).thenReturn(limitSnapshot);
    when(riskSnapshot.patterns()).thenReturn(patternSnapshot);
    when(limitSnapshot.override(any(LimitType.class))).thenReturn(Optional.empty());
    when(ruleEngine.evaluate(any(PatternContext.class), same(patternSnapshot)))
        .thenReturn(List.of());
  }

  private RiskCheckCommand commandWithStake(long amount) {
    return new RiskCheckCommand(USER, BET, Money.krw(amount), List.of("s-1"), NOW);
  }
}
