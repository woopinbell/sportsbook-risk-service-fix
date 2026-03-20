package com.sportsbook.risk.api;

import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.service.LimitRejection;
import com.sportsbook.risk.service.RiskCheckCommand;
import com.sportsbook.risk.service.RiskCheckOutcome;
import com.sportsbook.risk.service.RiskCheckService;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Critical-path entry point for {@code betting-service}. Translates the REST DTO into a {@link
 * RiskCheckCommand}, lets {@link RiskCheckService} do the evaluation, and renders the outcome back
 * as the response DTO.
 *
 * <p>Path prefix follows ADR-0004: {@code /internal/v1/risk/...} for service-to-service traffic.
 */
@RestController
@RequestMapping("/internal/v1/risk")
public class RiskCheckController {

  private final RiskCheckService service;
  private final Clock clock;

  public RiskCheckController(RiskCheckService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  @PostMapping("/check")
  public RiskCheckResponse check(@Valid @RequestBody RiskCheckRequest request) {
    RiskCheckCommand command =
        new RiskCheckCommand(
            request.userId(),
            request.betId(),
            request.stake(),
            request.selectionIds(),
            clock.instant());
    return toResponse(service.check(command));
  }

  private static RiskCheckResponse toResponse(RiskCheckOutcome outcome) {
    List<RiskCheckResponse.PatternFlag> flags =
        outcome.patternsFlagged().stream().map(RiskCheckController::toFlag).toList();
    if (outcome.approved()) {
      return new RiskCheckResponse(true, null, null, flags);
    }
    LimitRejection rejection = outcome.rejection().orElseThrow();
    RiskCheckResponse.LimitInfo info =
        new RiskCheckResponse.LimitInfo(
            rejection.currency(),
            rejection.current(),
            rejection.limit(),
            rejection.requested(),
            rejection.action());
    return new RiskCheckResponse(false, rejection.reason(), info, flags);
  }

  private static RiskCheckResponse.PatternFlag toFlag(PatternMatch match) {
    return new RiskCheckResponse.PatternFlag(
        match.ruleName(), match.action().name(), match.reason());
  }
}
