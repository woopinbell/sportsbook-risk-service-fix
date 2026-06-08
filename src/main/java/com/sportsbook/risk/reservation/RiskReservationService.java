package com.sportsbook.risk.reservation;

import com.sportsbook.risk.event.RiskEventPublisher;
import com.sportsbook.risk.pattern.PatternContext;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.pattern.RuleEngine;
import com.sportsbook.risk.policy.PatternAction;
import com.sportsbook.risk.service.LimitRejection;
import com.sportsbook.risk.service.RiskCheckCommand;
import com.sportsbook.risk.snapshot.PatternSnapshot;
import com.sportsbook.risk.snapshot.RiskSnapshotReader;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** Coordinates atomic limit admission with the existing rule-based pattern engine. */
@Service
public class RiskReservationService {

  private final RedisRiskReservationStore store;
  private final RiskSnapshotReader snapshots;
  private final RuleEngine rules;
  private final RiskEventPublisher publisher;
  private final MeterRegistry meters;

  public RiskReservationService(
      RedisRiskReservationStore store,
      RiskSnapshotReader snapshots,
      RuleEngine rules,
      RiskEventPublisher publisher,
      MeterRegistry meters) {
    this.store = store;
    this.snapshots = snapshots;
    this.rules = rules;
    this.publisher = publisher;
    this.meters = meters;
  }

  public RiskReservationOutcome reserve(RiskCheckCommand command) {
    PatternEvaluation patterns =
        store.needsPatternEvaluation(command)
            ? evaluatePatterns(command)
            : PatternEvaluation.empty();
    ReservationDecision decision = store.reserve(command, patterns.block(), patterns.matches());
    if (decision.status() == ReservationDecision.Status.CONFLICT) {
      count("conflict");
      throw new ReservationConflictException(command.betId());
    }
    if (!decision.approved()) {
      if (decision.replayed()) {
        count("replay");
        return RiskReservationOutcome.rejected(decision.rejection(), decision.patternsFlagged());
      }
      count("rejected");
      LimitRejection rejection = decision.rejection();
      meters.counter("risk_limit_violations_total", "reason", rejection.reason()).increment();
      if (patterns.isBlockingDecision(rejection)) {
        patterns.matches().forEach(match -> publishPattern(command, match));
        transitionCount("reject", "applied");
        return RiskReservationOutcome.rejected(rejection, decision.patternsFlagged());
      }
      publisher.publishLimitViolated(command.userId(), rejection, command.now());
      return RiskReservationOutcome.rejected(rejection, List.of());
    }
    if (decision.replayed()) {
      count("replay");
      return RiskReservationOutcome.approved(
          decision.state(), decision.expiresAt(), decision.patternsFlagged());
    }

    try {
      patterns.matches().forEach(match -> publishPattern(command, match));
      count("created");
      return RiskReservationOutcome.approved(
          decision.state(), decision.expiresAt(), decision.patternsFlagged());
    } catch (RuntimeException failure) {
      store.release(command.betId(), command.now());
      throw failure;
    }
  }

  public void commit(String betId, Instant now) {
    ReservationTransition transition = store.commit(betId, now);
    switch (transition) {
      case APPLIED -> transitionCount("commit", "applied");
      case REPLAYED -> transitionCount("commit", "replay");
      case NOT_FOUND, EXPIRED, TOMBSTONED -> {
        transitionCount(
            "commit",
            switch (transition) {
              case EXPIRED -> "expired";
              case TOMBSTONED -> "tombstoned";
              default -> "not_found";
            });
        throw new ReservationNotFoundException(betId);
      }
      case COMMITTED_CONFLICT ->
          throw new IllegalStateException("commit returned an invalid release-only transition");
    }
  }

  public void release(String betId, Instant now) {
    ReservationTransition transition = store.release(betId, now);
    switch (transition) {
      case APPLIED -> transitionCount("release", "applied");
      case REPLAYED -> transitionCount("release", "replay");
      case NOT_FOUND -> transitionCount("release", "not_found");
      case EXPIRED -> transitionCount("release", "expired");
      case TOMBSTONED -> transitionCount("release", "tombstoned");
      case COMMITTED_CONFLICT -> {
        transitionCount("release", "committed_conflict");
        throw new CommittedReservationReleaseException(betId);
      }
    }
  }

  private PatternEvaluation evaluatePatterns(RiskCheckCommand command) {
    PatternContext context =
        new PatternContext(
            command.userId(),
            command.betId(),
            command.stake(),
            command.selectionIds(),
            command.now());
    PatternSnapshot snapshot = snapshots.readPatterns(context);
    List<PatternMatch> matches = rules.evaluate(context, snapshot);
    LimitRejection block =
        matches.stream()
            .filter(match -> match.action() == PatternAction.BLOCK)
            .findFirst()
            .map(
                match ->
                    new LimitRejection(
                        "PATTERN_" + match.ruleName().replace('-', '_').toUpperCase(),
                        null,
                        0L,
                        0L,
                        0L,
                        PatternAction.BLOCK.name()))
            .orElse(null);
    return new PatternEvaluation(matches, block);
  }

  private void publishPattern(RiskCheckCommand command, PatternMatch match) {
    meters
        .counter(
            "risk_pattern_flags_total", "rule", match.ruleName(), "action", match.action().name())
        .increment();
    publisher.publishPatternSuspected(command.userId(), match, command.now());
  }

  private void count(String result) {
    meters.counter("risk_reservation_requests_total", "result", result).increment();
  }

  private void transitionCount(String operation, String result) {
    meters
        .counter("risk_reservation_transitions_total", "operation", operation, "result", result)
        .increment();
  }

  private record PatternEvaluation(List<PatternMatch> matches, LimitRejection block) {

    private PatternEvaluation {
      matches = List.copyOf(matches);
    }

    static PatternEvaluation empty() {
      return new PatternEvaluation(List.of(), null);
    }

    boolean isBlockingDecision(LimitRejection decision) {
      return block != null && block.equals(decision);
    }
  }
}
