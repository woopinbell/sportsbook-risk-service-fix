package com.sportsbook.risk.pattern;

import com.sportsbook.risk.policy.PatternAction;

/**
 * A rule's verdict on a single {@link PatternContext}. {@code reason} is a short English string
 * intended for two places: the {@code patternsFlagged} entry in the /internal/v1/risk/check
 * response and the {@code reason} field on the published {@code risk.pattern.suspected} Avro event.
 * Keep it free of currency formatting / user-locale concerns so consumers stay simple.
 *
 * @param ruleName stable identifier matching the yaml key ("rapid-betting", etc.) so dashboards and
 *     alerts can group by source
 */
public record PatternMatch(String ruleName, PatternAction action, String reason) {}
