package com.sportsbook.risk.reservation;

/** Raised when a caller tries to release an already committed reservation. */
public class CommittedReservationReleaseException extends RuntimeException {

  public CommittedReservationReleaseException(String betId) {
    super("Committed risk reservation " + betId + " cannot be released");
  }
}
