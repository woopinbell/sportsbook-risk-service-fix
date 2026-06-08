package com.sportsbook.risk.service;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.event.RiskEventPublisher;
import com.sportsbook.risk.limit.LimitResolver;
import com.sportsbook.risk.pattern.PatternContext;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.pattern.RuleEngine;
import com.sportsbook.risk.policy.PatternAction;
import com.sportsbook.risk.policy.RiskLimitProperties;
import com.sportsbook.risk.snapshot.LimitSnapshot;
import com.sportsbook.risk.snapshot.PatternSnapshot;
import com.sportsbook.risk.snapshot.RiskSnapshot;
import com.sportsbook.risk.snapshot.RiskSnapshotReader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Diagnostic risk evaluation for a candidate bet. Betting admission uses {@code
 * /internal/v1/risk/reservations}; this service keeps the original read API for operators and
 * compatibility callers. Its snapshot includes active reservation totals but does not create a
 * lease. Java still returns at the first limit breach in the established decision order.
 *
 * <p>Order matters and is intentionally simple-to-expensive:
 *
 * <ol>
 *   <li>{@code SINGLE_BET_MAX} — pure local comparison against the yaml threshold.
 *   <li>{@code STAKE_DAILY}, {@code STAKE_WEEKLY}, {@code STAKE_MONTHLY} — values from the first
 *       atomic snapshot, plus a pure resolver lookup.
 *   <li>{@code SELECTIONS_PER_MINUTE} — same shape, count-based.
 *   <li>Pattern rules — exercised by {@link RuleEngine}; a BLOCK match rejects the bet, SUSPECT /
 *       REVIEW matches surface in the response but do not reject.
 * </ol>
 *
 * <p>Two Micrometer instruments are exposed (Prometheus scrape, ADR-0007):
 *
 * <ul>
 *   <li>{@code risk_check_latency_seconds} — Timer wrapping every call.
 *   <li>{@code risk_limit_violations_total} / {@code risk_pattern_flags_total} — Counters tagged by
 *       reason / rule for dashboard breakdowns.
 * </ul>
 */
@Service
public class RiskCheckService {

  private static final List<LimitType> STAKE_LIMITS =
      List.of(LimitType.STAKE_DAILY, LimitType.STAKE_WEEKLY, LimitType.STAKE_MONTHLY);

  private final RiskLimitProperties policy;
  private final LimitResolver limitResolver;
  private final RiskSnapshotReader snapshots;
  private final RuleEngine ruleEngine;
  private final RiskEventPublisher publisher;
  private final MeterRegistry meters;
  private final Timer checkTimer;

  public RiskCheckService(
      RiskLimitProperties policy,
      LimitResolver limitResolver,
      RiskSnapshotReader snapshots,
      RuleEngine ruleEngine,
      RiskEventPublisher publisher,
      MeterRegistry meters) {
    this.policy = policy;
    this.limitResolver = limitResolver;
    this.snapshots = snapshots;
    this.ruleEngine = ruleEngine;
    this.publisher = publisher;
    this.meters = meters;
    this.checkTimer =
        Timer.builder("risk_check_latency_seconds")
            .description("Latency of the diagnostic /internal/v1/risk/check path")
            .register(meters);
  }

  public RiskCheckOutcome check(RiskCheckCommand cmd) {
    return checkTimer.record(() -> doCheck(cmd));
  }

  private RiskCheckOutcome doCheck(RiskCheckCommand cmd) {
    long stake = cmd.stake().amount();
    Currency currency = cmd.stake().currency();

    long singleMax = policy.singleBetMax(currency);
    if (stake > singleMax) {
      return reject(
          cmd,
          new LimitRejection(
              "SINGLE_BET_MAX_EXCEEDED",
              currency,
              0L,
              singleMax,
              stake,
              PatternAction.BLOCK.name()));
    }

    PatternContext ctx =
        new PatternContext(cmd.userId(), cmd.betId(), cmd.stake(), cmd.selectionIds(), cmd.now());
    RiskSnapshot snapshot = snapshots.read(cmd.userId(), currency, ctx);
    LimitSnapshot limits = snapshot.limits();
    for (LimitType type : STAKE_LIMITS) {
      long current = limits.current(type);
      long limit = limitResolver.resolveUserFromSnapshot(type, currency, limits.override(type));
      if (current + stake > limit) {
        return reject(
            cmd,
            new LimitRejection(
                type.name() + "_LIMIT_EXCEEDED",
                currency,
                current,
                limit,
                stake,
                PatternAction.BLOCK.name()));
      }
    }

    int requestedSelections = cmd.selectionIds().size();
    long selCurrent = limits.current(LimitType.SELECTIONS_PER_MINUTE);
    long selLimit =
        limitResolver.resolveUserFromSnapshot(
            LimitType.SELECTIONS_PER_MINUTE,
            currency,
            limits.override(LimitType.SELECTIONS_PER_MINUTE));
    if (selCurrent + requestedSelections > selLimit) {
      return reject(
          cmd,
          new LimitRejection(
              "SELECTIONS_PER_MINUTE_LIMIT_EXCEEDED",
              null,
              selCurrent,
              selLimit,
              requestedSelections,
              PatternAction.BLOCK.name()));
    }

    PatternSnapshot patterns = snapshot.patterns();
    List<PatternMatch> matches = ruleEngine.evaluate(ctx, patterns);
    for (PatternMatch m : matches) {
      meters
          .counter("risk_pattern_flags_total", "rule", m.ruleName(), "action", m.action().name())
          .increment();
      publisher.publishPatternSuspected(cmd.userId(), m, cmd.now());
    }

    Optional<PatternMatch> block =
        matches.stream().filter(m -> m.action() == PatternAction.BLOCK).findFirst();
    if (block.isPresent()) {
      meters
          .counter(
              "risk_limit_violations_total",
              "reason",
              "PATTERN_" + block.get().ruleName().replace('-', '_').toUpperCase())
          .increment();
      return RiskCheckOutcome.rejectedByPattern(block.get(), matches);
    }
    return RiskCheckOutcome.approved(matches);
  }

  private RiskCheckOutcome reject(RiskCheckCommand cmd, LimitRejection rejection) {
    meters.counter("risk_limit_violations_total", "reason", rejection.reason()).increment();
    publisher.publishLimitViolated(cmd.userId(), rejection, cmd.now());
    return RiskCheckOutcome.rejectedByLimit(rejection, List.of());
  }
}
