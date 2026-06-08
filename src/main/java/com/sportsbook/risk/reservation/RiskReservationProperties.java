package com.sportsbook.risk.reservation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the short-lived admission lease and committed replay record. */
@ConfigurationProperties(prefix = "risk.reservations")
public record RiskReservationProperties(Duration lease, Duration retention) {

  private static final Duration DEFAULT_LEASE = Duration.ofMinutes(2);
  private static final Duration DEFAULT_RETENTION = Duration.ofDays(32);

  public RiskReservationProperties {
    if (lease == null) {
      lease = DEFAULT_LEASE;
    }
    if (retention == null) {
      retention = DEFAULT_RETENTION;
    }
    if (lease.isZero() || lease.isNegative()) {
      throw new IllegalArgumentException("risk.reservations.lease must be positive");
    }
    if (retention.compareTo(lease) <= 0) {
      throw new IllegalArgumentException("risk.reservations.retention must exceed the lease");
    }
  }
}
