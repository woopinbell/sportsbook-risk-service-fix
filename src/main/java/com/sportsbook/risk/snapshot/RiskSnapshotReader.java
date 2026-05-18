package com.sportsbook.risk.snapshot;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.pattern.PatternContext;
import java.time.Instant;

/** Reads the point-in-time Redis facts used by one synchronous risk check. */
public interface RiskSnapshotReader {

  RiskSnapshot read(String userId, Currency currency, PatternContext context);

  LimitSnapshot readLimits(String userId, Currency currency, Instant now);

  PatternSnapshot readPatterns(PatternContext context);
}
