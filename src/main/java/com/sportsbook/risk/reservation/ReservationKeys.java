package com.sportsbook.risk.reservation;

/** Redis key conventions shared by the reservation scripts and diagnostic snapshots. */
public final class ReservationKeys {

  public static final String ACTIVE_COUNT = "risk:reservations:active";

  private static final String RESERVATION_PREFIX = "risk:reservation:";
  private static final String USER_PREFIX = "risk:reservations:user:";
  private static final String STAKE_SUM_SUFFIX = ":stake-sum";
  private static final String SELECTION_SUM_SUFFIX = ":selection-sum";

  private ReservationKeys() {}

  public static String reservation(String betId) {
    return RESERVATION_PREFIX + betId;
  }

  public static String userReservations(String userId) {
    return USER_PREFIX + userId;
  }

  public static String reservedStakeSum(String userId) {
    return userReservations(userId) + STAKE_SUM_SUFFIX;
  }

  public static String reservedSelectionSum(String userId) {
    return userReservations(userId) + SELECTION_SUM_SUFFIX;
  }
}
