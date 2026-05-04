package com.sportsbook.risk.snapshot;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.pattern.PatternContext;
import java.time.Instant;

/** Reads the two point-in-time Redis snapshots used by one synchronous risk check. */
public interface RiskSnapshotReader {

  LimitSnapshot readLimits(String userId, Currency currency, Instant now);

  PatternSnapshot readPatterns(PatternContext context);
}
