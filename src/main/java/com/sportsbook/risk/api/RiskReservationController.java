package com.sportsbook.risk.api;

import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.reservation.RiskReservationOutcome;
import com.sportsbook.risk.reservation.RiskReservationService;
import com.sportsbook.risk.service.LimitRejection;
import com.sportsbook.risk.service.RiskCheckCommand;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal API for atomic admission leases used by betting-service. */
@RestController
@RequestMapping("/internal/v1/risk/reservations")
public class RiskReservationController {

  private final RiskReservationService service;
  private final Clock clock;

  public RiskReservationController(RiskReservationService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  @PostMapping
  public RiskReservationResponse reserve(@Valid @RequestBody RiskCheckRequest request) {
    RiskCheckCommand command =
        new RiskCheckCommand(
            request.userId(),
            request.betId(),
            request.stake(),
            request.selectionIds(),
            clock.instant());
    return response(service.reserve(command));
  }

  @PutMapping("/{betId}/commit")
  public ResponseEntity<Void> commit(@PathVariable String betId) {
    service.commit(betId, clock.instant());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{betId}")
  public ResponseEntity<Void> release(@PathVariable String betId) {
    service.release(betId, clock.instant());
    return ResponseEntity.noContent().build();
  }

  private static RiskReservationResponse response(RiskReservationOutcome outcome) {
    List<RiskCheckResponse.PatternFlag> flags =
        outcome.patternsFlagged().stream().map(RiskReservationController::flag).toList();
    LimitRejection rejection = outcome.rejection().orElse(null);
    RiskCheckResponse.LimitInfo limitInfo =
        rejection == null
            ? null
            : new RiskCheckResponse.LimitInfo(
                rejection.currency(),
                rejection.current(),
                rejection.limit(),
                rejection.requested(),
                rejection.action());
    return new RiskReservationResponse(
        outcome.approved(),
        rejection == null ? null : rejection.reason(),
        limitInfo,
        flags,
        outcome.state(),
        outcome.expiresAt());
  }

  private static RiskCheckResponse.PatternFlag flag(PatternMatch match) {
    return new RiskCheckResponse.PatternFlag(
        match.ruleName(), match.action().name(), match.reason());
  }
}
