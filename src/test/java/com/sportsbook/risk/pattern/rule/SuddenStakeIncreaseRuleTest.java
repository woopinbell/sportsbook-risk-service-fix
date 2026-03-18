package com.sportsbook.risk.pattern.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sportsbook.protocol.value.Money;
import com.sportsbook.risk.pattern.PatternContext;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.pattern.UserBetHistory;
import com.sportsbook.risk.policy.PatternAction;
import com.sportsbook.risk.policy.RiskPatternProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SuddenStakeIncreaseRuleTest {

  private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");

  @Mock private UserBetHistory history;

  @Test
  void disabledRuleNeverFires() {
    RiskPatternProperties patterns = patterns(disabled());

    SuddenStakeIncreaseRule rule = new SuddenStakeIncreaseRule(patterns, history);

    assertThat(rule.evaluate(ctxWithStake(1_000_000L))).isEmpty();
  }

  @Test
  void skipsEvaluationForUsersWithoutEnoughHistory() {
    RiskPatternProperties patterns = patterns(enabled(10, 10, PatternAction.SUSPECT));
    when(history.recentStakeAmounts(anyString(), anyInt()))
        .thenReturn(List.of(5_000L, 6_000L)); // only 2 of the 10 required

    Optional<PatternMatch> match =
        new SuddenStakeIncreaseRule(patterns, history).evaluate(ctxWithStake(1_000_000L));

    assertThat(match).isEmpty();
  }

  @Test
  void firesWhenCandidateIsAtLeastMultiplierTimesMedian() {
    RiskPatternProperties patterns = patterns(enabled(3, 3, PatternAction.SUSPECT));
    when(history.recentStakeAmounts(anyString(), anyInt()))
        .thenReturn(List.of(1_000L, 2_000L, 3_000L)); // median = 2_000
    long candidate = 6_000L; // exactly 3 * 2_000

    Optional<PatternMatch> match =
        new SuddenStakeIncreaseRule(patterns, history).evaluate(ctxWithStake(candidate));

    assertThat(match).isPresent();
    PatternMatch m = match.get();
    assertThat(m.ruleName()).isEqualTo("sudden-stake-increase");
    assertThat(m.reason()).contains("median 2000").contains("3x");
  }

  @Test
  void belowMultiplierDoesNotFire() {
    RiskPatternProperties patterns = patterns(enabled(3, 3, PatternAction.SUSPECT));
    when(history.recentStakeAmounts(anyString(), anyInt()))
        .thenReturn(List.of(1_000L, 2_000L, 3_000L)); // median = 2_000
    long candidate = 5_000L; // 2.5x, below 3x

    Optional<PatternMatch> match =
        new SuddenStakeIncreaseRule(patterns, history).evaluate(ctxWithStake(candidate));

    assertThat(match).isEmpty();
  }

  @Test
  void zeroMedianGuardsAgainstFalseFires() {
    RiskPatternProperties patterns = patterns(enabled(3, 3, PatternAction.SUSPECT));
    when(history.recentStakeAmounts(anyString(), anyInt()))
        .thenReturn(List.of(0L, 0L, 0L)); // pathological yet possible after refunds

    Optional<PatternMatch> match =
        new SuddenStakeIncreaseRule(patterns, history).evaluate(ctxWithStake(1L));

    assertThat(match).isEmpty();
  }

  private static RiskPatternProperties patterns(RiskPatternProperties.SuddenStakeIncrease sudden) {
    return new RiskPatternProperties(null, sudden, null);
  }

  private static RiskPatternProperties.SuddenStakeIncrease disabled() {
    return new RiskPatternProperties.SuddenStakeIncrease(false, 10, 10, PatternAction.SUSPECT);
  }

  private static RiskPatternProperties.SuddenStakeIncrease enabled(
      int multiplier, int lookback, PatternAction action) {
    return new RiskPatternProperties.SuddenStakeIncrease(true, multiplier, lookback, action);
  }

  private static PatternContext ctxWithStake(long amount) {
    return new PatternContext("u-1", "bet-1", Money.krw(amount), List.of("s-1"), NOW);
  }
}
