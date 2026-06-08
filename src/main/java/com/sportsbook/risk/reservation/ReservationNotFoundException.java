package com.sportsbook.risk.reservation;

/** Raised when a reservation cannot be committed because it is missing or expired. */
public class ReservationNotFoundException extends RuntimeException {

  public ReservationNotFoundException(String betId) {
    super("Risk reservation " + betId + " was not found or has expired");
  }
}
