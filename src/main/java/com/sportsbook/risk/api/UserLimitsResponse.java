package com.sportsbook.risk.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sportsbook.protocol.value.Currency;
import java.util.List;

/**
 * Snapshot of the effective limits for a user, returned by {@code GET
 * /internal/v1/risk/limits/{userId}}. Each entry tells the operator both the value the service will
 * enforce and where that value came from (policy default vs. operator override) so admin tooling
 * can render the source line without a second round-trip.
 */
public record UserLimitsResponse(String userId, List<Entry> limits) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Entry(String limitType, Currency currency, long value, String source) {
    public static final String SOURCE_OVERRIDE = "OVERRIDE";
    public static final String SOURCE_POLICY = "POLICY";
  }
}
