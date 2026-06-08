package com.sportsbook.risk.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sportsbook.risk.reservation.ReservationState;
import java.time.Instant;
import java.util.List;

/** Response for atomic risk reservation admission. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RiskReservationResponse(
    boolean approved,
    String rejectionReason,
    RiskCheckResponse.LimitInfo limitInfo,
    List<RiskCheckResponse.PatternFlag> patternsFlagged,
    ReservationState reservationState,
    Instant expiresAt) {}
