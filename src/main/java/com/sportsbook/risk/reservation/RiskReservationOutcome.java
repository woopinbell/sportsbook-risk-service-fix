package com.sportsbook.risk.reservation;

import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.service.LimitRejection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Service-layer result mapped onto the reservation HTTP response. */
public record RiskReservationOutcome(
    boolean approved,
    Optional<LimitRejection> rejection,
    List<PatternMatch> patternsFlagged,
    ReservationState state,
    Instant expiresAt) {

  public RiskReservationOutcome {
    rejection = rejection == null ? Optional.empty() : rejection;
    patternsFlagged = List.copyOf(patternsFlagged);
  }

  public static RiskReservationOutcome approved(
      ReservationState state, Instant expiresAt, List<PatternMatch> patterns) {
    return new RiskReservationOutcome(true, Optional.empty(), patterns, state, expiresAt);
  }

  public static RiskReservationOutcome rejected(
      LimitRejection rejection, List<PatternMatch> patterns) {
    return new RiskReservationOutcome(false, Optional.of(rejection), patterns, null, null);
  }
}
