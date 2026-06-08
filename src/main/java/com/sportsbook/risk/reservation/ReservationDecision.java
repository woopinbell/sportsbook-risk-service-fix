package com.sportsbook.risk.reservation;

import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.service.LimitRejection;
import java.time.Instant;
import java.util.List;

/** Atomic Redis admission result, including the pattern verdicts retained for idempotent replay. */
public record ReservationDecision(
    Status status,
    ReservationState state,
    Instant expiresAt,
    LimitRejection rejection,
    boolean replayed,
    List<PatternMatch> patternsFlagged) {

  public ReservationDecision {
    patternsFlagged = List.copyOf(patternsFlagged);
  }

  public enum Status {
    APPROVED,
    REJECTED,
    CONFLICT
  }

  public static ReservationDecision approved(
      ReservationState state, Instant expiresAt, boolean replayed) {
    return approved(state, expiresAt, replayed, List.of());
  }

  public static ReservationDecision approved(
      ReservationState state,
      Instant expiresAt,
      boolean replayed,
      List<PatternMatch> patternsFlagged) {
    return new ReservationDecision(
        Status.APPROVED, state, expiresAt, null, replayed, patternsFlagged);
  }

  public static ReservationDecision rejected(LimitRejection rejection) {
    return rejected(rejection, false, List.of());
  }

  public static ReservationDecision rejected(LimitRejection rejection, boolean replayed) {
    return rejected(rejection, replayed, List.of());
  }

  public static ReservationDecision rejected(
      LimitRejection rejection, boolean replayed, List<PatternMatch> patternsFlagged) {
    return new ReservationDecision(
        Status.REJECTED, null, null, rejection, replayed, patternsFlagged);
  }

  public static ReservationDecision conflict() {
    return new ReservationDecision(Status.CONFLICT, null, null, null, false, List.of());
  }

  public boolean approved() {
    return status == Status.APPROVED;
  }
}
