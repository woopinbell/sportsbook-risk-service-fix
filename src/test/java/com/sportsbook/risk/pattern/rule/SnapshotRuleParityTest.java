package com.sportsbook.risk.pattern.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sportsbook.protocol.value.Money;
import com.sportsbook.risk.pattern.PatternContext;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.pattern.PatternRule;
import com.sportsbook.risk.pattern.RuleEngine;
import com.sportsbook.risk.pattern.UserBetHistory;
import com.sportsbook.risk.policy.PatternAction;
import com.sportsbook.risk.policy.RiskPatternProperties;
import com.sportsbook.risk.snapshot.PatternSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SnapshotRuleParityTest {

  private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");
  private static final PatternContext CONTEXT =
      new PatternContext("u-1", "bet-1", Money.krw(1_000L), List.of("s-1"), NOW);

  @Mock private UserBetHistory history;

  @Test
  void snapshotFactsProduceTheSameMatchesReasonsActionsAndOrder() {
    when(history.countBetsBetween(anyString(), any(Instant.class), any(Instant.class)))
        .thenReturn(29L);
    when(history.recentStakeAmounts(anyString(), anyInt()))
        .thenReturn(List.of(50L, 50L, 50L, 50L, 50L, 50L, 50L, 50L, 50L, 50L));
    when(history.countSelectionBets(
            anyString(), anyString(), any(Duration.class), any(Instant.class)))
        .thenReturn(5L);

    RiskPatternProperties patterns =
        new RiskPatternProperties(
            new RiskPatternProperties.RapidBetting(true, 60, 30, PatternAction.SUSPECT),
            new RiskPatternProperties.SuddenStakeIncrease(true, 10, 10, PatternAction.BLOCK),
            new RiskPatternProperties.RepeatedSameSelection(true, 24, 5, PatternAction.REVIEW));
    List<PatternRule> rules =
        List.of(
            new RapidBettingRule(patterns, history),
            new SuddenStakeIncreaseRule(patterns, history),
            new RepeatedSameSelectionRule(patterns, history));
    RuleEngine engine = new RuleEngine(rules);
    PatternSnapshot snapshot =
        PatternSnapshot.successful(
            29L, List.of(50L, 50L, 50L, 50L, 50L, 50L, 50L, 50L, 50L, 50L), Map.of("s-1", 5L));

    List<PatternMatch> direct = engine.evaluate(CONTEXT);
    List<PatternMatch> captured = engine.evaluate(CONTEXT, snapshot);

    assertThat(captured).containsExactlyElementsOf(direct);
    assertThat(captured)
        .extracting(PatternMatch::ruleName)
        .containsExactly("rapid-betting", "sudden-stake-increase", "repeated-same-selection");
  }
}
