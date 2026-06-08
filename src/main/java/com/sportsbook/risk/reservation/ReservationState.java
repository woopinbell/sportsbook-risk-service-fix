package com.sportsbook.risk.reservation;

/** Successful state exposed by the risk reservation response. Terminal tombstones stay internal. */
public enum ReservationState {
  RESERVED,
  COMMITTED
}
