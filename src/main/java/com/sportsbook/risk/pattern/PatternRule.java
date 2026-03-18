package com.sportsbook.risk.pattern;

import java.util.Optional;

/**
 * Strategy contract for a single suspicious-pattern rule. Concrete rules are Spring beans so the
 * yaml-driven on/off flag, the {@link com.sportsbook.risk.pattern.UserBetHistory} the rule reads
 * from, and the {@link com.sportsbook.risk.policy.RiskPatternProperties} thresholds are all
 * injected — no rule reaches across to Redis or the config object directly.
 *
 * <p>Returns {@link Optional#empty()} when the rule did not fire. {@link RuleEngine} aggregates
 * matches from every rule and lets {@link com.sportsbook.risk.policy.PatternAction#BLOCK} short-
 * circuit the check API's critical path; SUSPECT / REVIEW matches are reported back to the caller
 * and published asynchronously without rejecting the bet.
 */
public interface PatternRule {

  Optional<PatternMatch> evaluate(PatternContext context);
}
