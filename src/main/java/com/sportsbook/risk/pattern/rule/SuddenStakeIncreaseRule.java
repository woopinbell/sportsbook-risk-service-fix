package com.sportsbook.risk.pattern.rule;

import com.sportsbook.risk.pattern.PatternContext;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.pattern.PatternRule;
import com.sportsbook.risk.pattern.UserBetHistory;
import com.sportsbook.risk.policy.RiskPatternProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Fires when the candidate bet's stake is at least {@code multiplierThreshold} times the median
 * stake over the most recent {@code lookbackBets} placed by the same user. Median is more robust
 * than mean against a single outlier — one big honest bet should not erase the user's "normal"
 * baseline for the next evaluation.
 *
 * <p>Skips evaluation when fewer than {@code lookbackBets} historical bets exist; a brand-new user
 * has no comparable baseline yet and the rule would otherwise fire on every bet.
 */
@Component
public class SuddenStakeIncreaseRule implements PatternRule {

  static final String NAME = "sudden-stake-increase";

  private final RiskPatternProperties.SuddenStakeIncrease cfg;
  private final UserBetHistory history;

  public SuddenStakeIncreaseRule(RiskPatternProperties patterns, UserBetHistory history) {
    this.cfg = patterns.suddenStakeIncrease();
    this.history = history;
  }

  @Override
  public Optional<PatternMatch> evaluate(PatternContext context) {
    if (!cfg.enabled()) {
      return Optional.empty();
    }
    List<Long> recent = history.recentStakeAmounts(context.userId(), cfg.lookbackBets());
    if (recent.size() < cfg.lookbackBets()) {
      return Optional.empty();
    }
    long median = median(recent);
    if (median <= 0) {
      return Optional.empty();
    }
    long candidate = context.stake().amount();
    if (candidate < (long) cfg.multiplierThreshold() * median) {
      return Optional.empty();
    }
    String reason =
        "stake "
            + candidate
            + " is "
            + cfg.multiplierThreshold()
            + "x or more above median "
            + median
            + " of last "
            + cfg.lookbackBets()
            + " bets";
    return Optional.of(new PatternMatch(NAME, cfg.action(), reason));
  }

  private static long median(List<Long> values) {
    List<Long> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    int size = sorted.size();
    if (size % 2 == 1) {
      return sorted.get(size / 2);
    }
    long lower = sorted.get(size / 2 - 1);
    long upper = sorted.get(size / 2);
    return lower + (upper - lower) / 2L;
  }
}
