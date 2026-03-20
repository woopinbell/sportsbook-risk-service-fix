package com.sportsbook.risk.service;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.counter.LimitKeys;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.counter.SlidingWindowCounter;
import com.sportsbook.risk.limit.LimitResolver;
import com.sportsbook.risk.pattern.PatternContext;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.pattern.RuleEngine;
import com.sportsbook.risk.policy.PatternAction;
import com.sportsbook.risk.policy.RiskLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Synchronous risk evaluation for a candidate bet. Executes in the betting-service critical path,
 * so the implementation makes a fixed bounded number of Redis round-trips (one per sliding-window
 * limit, one HGET per overridden limit and zero per yaml-default limit, then the pattern rule
 * queries) and returns at the first limit breach.
 *
 * <p>Order matters and is intentionally simple-to-expensive:
 *
 * <ol>
 *   <li>{@code SINGLE_BET_MAX} — pure local comparison against the yaml threshold.
 *   <li>{@code STAKE_DAILY}, {@code STAKE_WEEKLY}, {@code STAKE_MONTHLY} — one Redis Lua peek each
 *       via {@link SlidingWindowCounter#currentSum}, plus a resolver lookup.
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
  private final SlidingWindowCounter counter;
  private final RuleEngine ruleEngine;
  private final MeterRegistry meters;
  private final Timer checkTimer;

  public RiskCheckService(
      RiskLimitProperties policy,
      LimitResolver limitResolver,
      SlidingWindowCounter counter,
      RuleEngine ruleEngine,
      MeterRegistry meters) {
    this.policy = policy;
    this.limitResolver = limitResolver;
    this.counter = counter;
    this.ruleEngine = ruleEngine;
    this.meters = meters;
    this.checkTimer =
        Timer.builder("risk_check_latency_seconds")
            .description("Latency of /internal/v1/risk/check critical path")
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
          new LimitRejection(
              "SINGLE_BET_MAX_EXCEEDED",
              currency,
              0L,
              singleMax,
              stake,
              PatternAction.BLOCK.name()));
    }

    for (LimitType type : STAKE_LIMITS) {
      String key = LimitKeys.userKey(cmd.userId(), type);
      long current = counter.currentSum(key, type.window(), cmd.now());
      long limit = limitResolver.resolveUser(cmd.userId(), type, currency);
      if (current + stake > limit) {
        return reject(
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
    String selKey = LimitKeys.userKey(cmd.userId(), LimitType.SELECTIONS_PER_MINUTE);
    long selCurrent =
        counter.currentSum(selKey, LimitType.SELECTIONS_PER_MINUTE.window(), cmd.now());
    long selLimit =
        limitResolver.resolveUser(cmd.userId(), LimitType.SELECTIONS_PER_MINUTE, currency);
    if (selCurrent + requestedSelections > selLimit) {
      return reject(
          new LimitRejection(
              "SELECTIONS_PER_MINUTE_LIMIT_EXCEEDED",
              null,
              selCurrent,
              selLimit,
              requestedSelections,
              PatternAction.BLOCK.name()));
    }

    PatternContext ctx =
        new PatternContext(cmd.userId(), cmd.betId(), cmd.stake(), cmd.selectionIds(), cmd.now());
    List<PatternMatch> matches = ruleEngine.evaluate(ctx);
    matches.forEach(
        m ->
            meters
                .counter(
                    "risk_pattern_flags_total", "rule", m.ruleName(), "action", m.action().name())
                .increment());

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

  private RiskCheckOutcome reject(LimitRejection rejection) {
    meters.counter("risk_limit_violations_total", "reason", rejection.reason()).increment();
    return RiskCheckOutcome.rejectedByLimit(rejection, List.of());
  }
}
