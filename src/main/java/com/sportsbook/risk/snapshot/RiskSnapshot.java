package com.sportsbook.risk.snapshot;

/** Point-in-time limit and pattern facts used by one synchronous risk decision. */
public record RiskSnapshot(LimitSnapshot limits, PatternSnapshot patterns) {}
