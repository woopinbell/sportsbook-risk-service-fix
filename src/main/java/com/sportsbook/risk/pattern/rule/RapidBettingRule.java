package com.sportsbook.risk.pattern.rule;

import com.sportsbook.risk.pattern.PatternContext;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.pattern.PatternRule;
import com.sportsbook.risk.pattern.UserBetHistory;
import com.sportsbook.risk.policy.RiskPatternProperties;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Fires when the user has placed at least {@code maxBets} bets in the trailing {@code
 * windowSeconds} ending at {@link PatternContext#now()}. The candidate bet itself is not yet
 * recorded — the threshold compares the historical count plus one (the candidate) against the
 * configured cap, so a {@code maxBets=30} setting still triggers on the 30th submission.
 */
@Component
public class RapidBettingRule implements PatternRule {

  static final String NAME = "rapid-betting";

  private final RiskPatternProperties.RapidBetting cfg;
  private final UserBetHistory history;

  public RapidBettingRule(RiskPatternProperties patterns, UserBetHistory history) {
    this.cfg = patterns.rapidBetting();
    this.history = history;
  }

  @Override
  public Optional<PatternMatch> evaluate(PatternContext context) {
    if (!cfg.enabled()) {
      return Optional.empty();
    }
    long recent =
        history.countBetsBetween(
            context.userId(), context.now().minusSeconds(cfg.windowSeconds()), context.now());
    long withCandidate = recent + 1;
    if (withCandidate < cfg.maxBets()) {
      return Optional.empty();
    }
    String reason =
        withCandidate + " bets in last " + cfg.windowSeconds() + "s reaches cap " + cfg.maxBets();
    return Optional.of(new PatternMatch(NAME, cfg.action(), reason));
  }
}
