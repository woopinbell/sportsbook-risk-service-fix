package com.sportsbook.risk.pattern;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Aggregates every {@link PatternRule} bean Spring discovered and returns every match. Rules run in
 * declaration order; callers (the check API today, the Kafka consumer publishing {@code
 * risk.pattern.suspected} tomorrow) decide what to do with the verdicts.
 *
 * <p>BLOCK is treated by the caller, not here — the engine returns the verdict list and lets the
 * controller decide whether any single {@link com.sportsbook.risk.policy.PatternAction#BLOCK}
 * causes the bet to be rejected on the critical path. Keeping the engine purely a fold keeps it
 * trivially testable and reusable from the consumer side where BLOCK has different semantics (the
 * bet has already been placed by the time we consume {@code bet.placed}, so the consumer just
 * publishes the event).
 */
@Component
public class RuleEngine {

  private final List<PatternRule> rules;

  public RuleEngine(List<PatternRule> rules) {
    this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
  }

  public List<PatternMatch> evaluate(PatternContext context) {
    Objects.requireNonNull(context, "context");
    return rules.stream()
        .map(rule -> rule.evaluate(context))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .toList();
  }
}
