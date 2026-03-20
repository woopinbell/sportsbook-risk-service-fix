package com.sportsbook.risk.service;

import com.sportsbook.protocol.value.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Service-layer input for {@link RiskCheckService}. Controllers and Kafka consumers translate the
 * incoming DTO (REST or Avro) into this record and inject the evaluation time so the service stays
 * deterministic and unit-testable against a fixed clock.
 */
public record RiskCheckCommand(
    String userId, String betId, Money stake, List<String> selectionIds, Instant now) {

  public RiskCheckCommand {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(betId, "betId");
    Objects.requireNonNull(stake, "stake");
    Objects.requireNonNull(selectionIds, "selectionIds");
    Objects.requireNonNull(now, "now");
    selectionIds = List.copyOf(selectionIds);
  }
}
