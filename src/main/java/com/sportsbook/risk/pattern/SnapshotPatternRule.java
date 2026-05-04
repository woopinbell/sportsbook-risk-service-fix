package com.sportsbook.risk.pattern;

import com.sportsbook.risk.snapshot.PatternSnapshot;
import java.util.Optional;

/** Pattern rule variant that evaluates the same decision against pre-read Redis facts. */
public interface SnapshotPatternRule extends PatternRule {

  Optional<PatternMatch> evaluate(PatternContext context, PatternSnapshot snapshot);
}
