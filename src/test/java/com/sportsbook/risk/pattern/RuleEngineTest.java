package com.sportsbook.risk.pattern;

import static org.assertj.core.api.Assertions.assertThat;

import com.sportsbook.protocol.value.Money;
import com.sportsbook.risk.policy.PatternAction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

  private static final PatternContext CTX =
      new PatternContext(
          "u-1",
          "bet-1",
          Money.krw(10_000L),
          List.of("s-1"),
          Instant.parse("2026-05-28T10:00:00Z"));

  @Test
  void emptyRuleListProducesEmptyMatches() {
    RuleEngine engine = new RuleEngine(List.of());

    assertThat(engine.evaluate(CTX)).isEmpty();
  }

  @Test
  void nonFiringRulesProduceEmptyMatches() {
    RuleEngine engine =
        new RuleEngine(List.of(constant(Optional.empty()), constant(Optional.empty())));

    assertThat(engine.evaluate(CTX)).isEmpty();
  }

  @Test
  void returnsEveryMatchInDeclarationOrder() {
    PatternMatch first = new PatternMatch("alpha", PatternAction.SUSPECT, "alpha hit");
    PatternMatch second = new PatternMatch("beta", PatternAction.REVIEW, "beta hit");
    RuleEngine engine =
        new RuleEngine(
            List.of(
                constant(Optional.of(first)),
                constant(Optional.empty()),
                constant(Optional.of(second))));

    List<PatternMatch> matches = engine.evaluate(CTX);

    assertThat(matches).containsExactly(first, second);
  }

  @Test
  void doesNotShortCircuitOnBlockAction() {
    // Engine returns every match; the caller decides whether BLOCK rejects the bet.
    PatternMatch block = new PatternMatch("alpha", PatternAction.BLOCK, "block hit");
    PatternMatch suspect = new PatternMatch("beta", PatternAction.SUSPECT, "suspect hit");
    RuleEngine engine =
        new RuleEngine(List.of(constant(Optional.of(block)), constant(Optional.of(suspect))));

    assertThat(engine.evaluate(CTX)).containsExactly(block, suspect);
  }

  private static PatternRule constant(Optional<PatternMatch> verdict) {
    return ctx -> verdict;
  }
}
