package com.sportsbook.risk.reservation;

/** Result of an idempotent commit or release transition. */
public enum ReservationTransition {
  APPLIED,
  REPLAYED,
  NOT_FOUND,
  EXPIRED,
  TOMBSTONED,
  COMMITTED_CONFLICT
}
