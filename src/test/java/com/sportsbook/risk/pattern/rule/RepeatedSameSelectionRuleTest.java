package com.sportsbook.risk.pattern.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.sportsbook.protocol.value.Money;
import com.sportsbook.risk.pattern.PatternContext;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.pattern.UserBetHistory;
import com.sportsbook.risk.policy.PatternAction;
import com.sportsbook.risk.policy.RiskPatternProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RepeatedSameSelectionRuleTest {

  private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");

  @Mock private UserBetHistory history;

  @Test
  void disabledRuleNeverFires() {
    RiskPatternProperties patterns = patterns(disabled());

    Optional<PatternMatch> match =
        new RepeatedSameSelectionRule(patterns, history)
            .evaluate(ctxWithSelections(List.of("s-1")));

    assertThat(match).isEmpty();
  }

  @Test
  void newSelectionPassesUnderCap() {
    RiskPatternProperties patterns = patterns(enabled(24, 5, PatternAction.REVIEW));
    when(history.countSelectionBets(
            anyString(), anyString(), any(Duration.class), any(Instant.class)))
        .thenReturn(0L);

    Optional<PatternMatch> match =
        new RepeatedSameSelectionRule(patterns, history)
            .evaluate(ctxWithSelections(List.of("s-1")));

    assertThat(match).isEmpty();
  }

  @Test
  void firesOnSelectionThatPushesCountPastCap() {
    RiskPatternProperties patterns = patterns(enabled(24, 5, PatternAction.REVIEW));
    when(history.countSelectionBets(
            anyString(), eq("s-hot"), any(Duration.class), any(Instant.class)))
        .thenReturn(5L); // candidate makes 6 > 5

    Optional<PatternMatch> match =
        new RepeatedSameSelectionRule(patterns, history)
            .evaluate(ctxWithSelections(List.of("s-cold", "s-hot")));

    assertThat(match).isPresent();
    PatternMatch m = match.get();
    assertThat(m.ruleName()).isEqualTo("repeated-same-selection");
    assertThat(m.action()).isEqualTo(PatternAction.REVIEW);
    assertThat(m.reason()).contains("s-hot").contains("6 times").contains("cap 5");
  }

  @Test
  void usesFirstHotSelectionInDeclarationOrder() {
    RiskPatternProperties patterns = patterns(enabled(24, 2, PatternAction.SUSPECT));
    when(history.countSelectionBets(
            anyString(), eq("s-a"), any(Duration.class), any(Instant.class)))
        .thenReturn(2L);
    // s-b would also fire if checked, but order should stop at s-a.

    Optional<PatternMatch> match =
        new RepeatedSameSelectionRule(patterns, history)
            .evaluate(ctxWithSelections(List.of("s-a", "s-b")));

    assertThat(match).isPresent();
    assertThat(match.get().reason()).contains("s-a");
  }

  private static RiskPatternProperties patterns(
      RiskPatternProperties.RepeatedSameSelection repeated) {
    return new RiskPatternProperties(null, null, repeated);
  }

  private static RiskPatternProperties.RepeatedSameSelection disabled() {
    return new RiskPatternProperties.RepeatedSameSelection(false, 24, 5, PatternAction.REVIEW);
  }

  private static RiskPatternProperties.RepeatedSameSelection enabled(
      int windowHours, int maxCount, PatternAction action) {
    return new RiskPatternProperties.RepeatedSameSelection(true, windowHours, maxCount, action);
  }

  private static PatternContext ctxWithSelections(List<String> selectionIds) {
    return new PatternContext("u-1", "bet-1", Money.krw(10_000L), selectionIds, NOW);
  }
}
