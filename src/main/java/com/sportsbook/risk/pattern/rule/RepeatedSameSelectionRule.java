package com.sportsbook.risk.pattern.rule;

import com.sportsbook.risk.pattern.PatternContext;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.pattern.PatternRule;
import com.sportsbook.risk.pattern.UserBetHistory;
import com.sportsbook.risk.policy.RiskPatternProperties;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Fires when the candidate bet contains a selection the user has already bet on more than {@code
 * maxCount} times in the trailing {@code windowHours}. The rule fires on the first matching
 * selection found in the slip; the reason includes that selection so downstream consumers can point
 * to it directly without re-running the rule themselves.
 */
@Component
public class RepeatedSameSelectionRule implements PatternRule {

  static final String NAME = "repeated-same-selection";

  private final RiskPatternProperties.RepeatedSameSelection cfg;
  private final UserBetHistory history;

  public RepeatedSameSelectionRule(RiskPatternProperties patterns, UserBetHistory history) {
    this.cfg = patterns.repeatedSameSelection();
    this.history = history;
  }

  @Override
  public Optional<PatternMatch> evaluate(PatternContext context) {
    if (!cfg.enabled()) {
      return Optional.empty();
    }
    Duration window = Duration.ofHours(cfg.windowHours());
    for (String selectionId : context.selectionIds()) {
      long prior = history.countSelectionBets(context.userId(), selectionId, window, context.now());
      long withCandidate = prior + 1;
      if (withCandidate > cfg.maxCount()) {
        String reason =
            "selection "
                + selectionId
                + " bet "
                + withCandidate
                + " times in last "
                + cfg.windowHours()
                + "h exceeds cap "
                + cfg.maxCount();
        return Optional.of(new PatternMatch(NAME, cfg.action(), reason));
      }
    }
    return Optional.empty();
  }
}
