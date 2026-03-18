package com.sportsbook.risk.pattern.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
class RapidBettingRuleTest {

  private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");
  private static final PatternContext CTX =
      new PatternContext("u-1", "bet-1", Money.krw(10_000L), List.of("s-1"), NOW);

  @Mock private UserBetHistory history;

  @Test
  void disabledRuleNeverFires() {
    RiskPatternProperties patterns = patternsWith(disabledRapid());

    RapidBettingRule rule = new RapidBettingRule(patterns, history);

    assertThat(rule.evaluate(CTX)).isEmpty();
  }

  @Test
  void belowCapPassesEvenIncludingCandidate() {
    RiskPatternProperties patterns = patternsWith(enabledRapid(30, PatternAction.SUSPECT));
    // 28 historical + 1 candidate = 29 < 30
    when(history.countBetsBetween(anyString(), any(Instant.class), any(Instant.class)))
        .thenReturn(28L);

    RapidBettingRule rule = new RapidBettingRule(patterns, history);

    assertThat(rule.evaluate(CTX)).isEmpty();
  }

  @Test
  void firesWhenCandidateReachesCap() {
    RiskPatternProperties patterns = patternsWith(enabledRapid(30, PatternAction.SUSPECT));
    when(history.countBetsBetween(anyString(), any(Instant.class), any(Instant.class)))
        .thenReturn(29L);

    Optional<PatternMatch> match = new RapidBettingRule(patterns, history).evaluate(CTX);

    assertThat(match).isPresent();
    PatternMatch m = match.get();
    assertThat(m.ruleName()).isEqualTo("rapid-betting");
    assertThat(m.action()).isEqualTo(PatternAction.SUSPECT);
    assertThat(m.reason()).contains("30 bets").contains("cap 30");
  }

  @Test
  void propagatesConfiguredAction() {
    RiskPatternProperties patterns = patternsWith(enabledRapid(5, PatternAction.BLOCK));
    when(history.countBetsBetween(anyString(), any(Instant.class), any(Instant.class)))
        .thenReturn(10L);

    Optional<PatternMatch> match = new RapidBettingRule(patterns, history).evaluate(CTX);

    assertThat(match).isPresent();
    assertThat(match.get().action()).isEqualTo(PatternAction.BLOCK);
  }

  private static RiskPatternProperties patternsWith(RiskPatternProperties.RapidBetting rapid) {
    return new RiskPatternProperties(rapid, null, null);
  }

  private static RiskPatternProperties.RapidBetting disabledRapid() {
    return new RiskPatternProperties.RapidBetting(false, 60, 30, PatternAction.SUSPECT);
  }

  private static RiskPatternProperties.RapidBetting enabledRapid(
      int maxBets, PatternAction action) {
    return new RiskPatternProperties.RapidBetting(true, 60, maxBets, action);
  }
}
