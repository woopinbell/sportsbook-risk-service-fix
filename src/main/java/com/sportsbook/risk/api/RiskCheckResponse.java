package com.sportsbook.risk.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sportsbook.protocol.value.Currency;
import java.util.List;

/**
 * REST DTO returned from {@code POST /internal/v1/risk/check}. {@code @JsonInclude(NON_NULL)} trims
 * optional fields so an approved response is just {@code {"approved":true,"patternsFlagged":[]}} on
 * the wire.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RiskCheckResponse(
    boolean approved,
    String rejectionReason,
    LimitInfo limitInfo,
    List<PatternFlag> patternsFlagged) {

  /**
   * Breakdown of the single limit that tripped the check. {@code currency} is omitted (null) for
   * count-based limits like {@code SELECTIONS_PER_MINUTE_LIMIT_EXCEEDED}.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record LimitInfo(
      Currency currency, long current, long limit, long requested, String action) {}

  /** Pattern-rule verdict mirrored from the engine for the JSON caller. */
  public record PatternFlag(String ruleName, String action, String reason) {}
}
