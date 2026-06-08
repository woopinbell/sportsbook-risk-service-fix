package com.sportsbook.risk.reservation;

/** Raised when one bet identifier is reused for different admission input. */
public class ReservationConflictException extends RuntimeException {

  public ReservationConflictException(String betId) {
    super("Risk reservation " + betId + " already exists with a different request");
  }
}
