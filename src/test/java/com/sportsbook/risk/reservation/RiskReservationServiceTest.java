package com.sportsbook.risk.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sportsbook.protocol.value.Money;
import com.sportsbook.risk.event.RiskEventPublisher;
import com.sportsbook.risk.pattern.PatternContext;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.pattern.RuleEngine;
import com.sportsbook.risk.policy.PatternAction;
import com.sportsbook.risk.service.RiskCheckCommand;
import com.sportsbook.risk.snapshot.PatternSnapshot;
import com.sportsbook.risk.snapshot.RiskSnapshotReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskReservationServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final Instant EXPIRES = NOW.plusSeconds(120);

  @Mock private RedisRiskReservationStore store;
  @Mock private RiskSnapshotReader snapshots;
  @Mock private RuleEngine rules;
  @Mock private RiskEventPublisher publisher;
  @Mock private PatternSnapshot patternSnapshot;

  private RiskReservationService service;
  private SimpleMeterRegistry meters;

  @BeforeEach
  void setUp() {
    meters = new SimpleMeterRegistry();
    service = new RiskReservationService(store, snapshots, rules, publisher, meters);
  }

  @Test
  void newlyCreatedReservationEvaluatesPatterns() {
    when(store.needsPatternEvaluation(any())).thenReturn(true);
    when(snapshots.readPatterns(any())).thenReturn(patternSnapshot);
    PatternMatch flag = new PatternMatch("rapid-betting", PatternAction.SUSPECT, "rapid");
    when(rules.evaluate(any(), eq(patternSnapshot))).thenReturn(List.of(flag));
    when(store.reserve(
            any(), nullable(com.sportsbook.risk.service.LimitRejection.class), eq(List.of(flag))))
        .thenReturn(
            ReservationDecision.approved(ReservationState.RESERVED, EXPIRES, false, List.of(flag)));

    RiskReservationOutcome outcome = service.reserve(command());

    assertThat(outcome.approved()).isTrue();
    assertThat(outcome.state()).isEqualTo(ReservationState.RESERVED);
    assertThat(outcome.patternsFlagged()).containsExactly(flag);
    verify(publisher).publishPatternSuspected("user-1", flag, NOW);
  }

  @Test
  void replayDoesNotReevaluateTimeDependentPatterns() {
    PatternMatch originalFlag =
        new PatternMatch("rapid-betting", PatternAction.SUSPECT, "original decision");
    when(store.reserve(
            any(), nullable(com.sportsbook.risk.service.LimitRejection.class), anyList()))
        .thenReturn(
            ReservationDecision.approved(
                ReservationState.COMMITTED, null, true, List.of(originalFlag)));

    RiskReservationOutcome outcome = service.reserve(command());

    assertThat(outcome.approved()).isTrue();
    assertThat(outcome.state()).isEqualTo(ReservationState.COMMITTED);
    assertThat(outcome.patternsFlagged()).containsExactly(originalFlag);
    verify(snapshots, never()).readPatterns(any(PatternContext.class));
  }

  @Test
  void blockingPatternIsPersistedAtomicallyBeforeReturningRejection() {
    when(store.needsPatternEvaluation(any())).thenReturn(true);
    when(snapshots.readPatterns(any())).thenReturn(patternSnapshot);
    PatternMatch block = new PatternMatch("rapid-betting", PatternAction.BLOCK, "blocked");
    when(rules.evaluate(any(), eq(patternSnapshot))).thenReturn(List.of(block));
    com.sportsbook.risk.service.LimitRejection rejection =
        new com.sportsbook.risk.service.LimitRejection(
            "PATTERN_RAPID_BETTING", null, 0L, 0L, 0L, PatternAction.BLOCK.name());
    when(store.reserve(any(), eq(rejection), eq(List.of(block))))
        .thenReturn(ReservationDecision.rejected(rejection, false, List.of(block)));

    RiskReservationOutcome outcome = service.reserve(command());

    assertThat(outcome.approved()).isFalse();
    assertThat(outcome.rejection().orElseThrow().reason()).isEqualTo("PATTERN_RAPID_BETTING");
    assertThat(outcome.patternsFlagged()).containsExactly(block);
    verify(store).reserve(any(), eq(rejection), eq(List.of(block)));
    verify(store, never()).release(any(), any());
    assertThat(
            meters
                .counter(
                    "risk_reservation_transitions_total",
                    "operation",
                    "reject",
                    "result",
                    "applied")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void changedPayloadConflictIsDefinitive() {
    when(store.reserve(
            any(), nullable(com.sportsbook.risk.service.LimitRejection.class), anyList()))
        .thenReturn(ReservationDecision.conflict());

    assertThatThrownBy(() -> service.reserve(command()))
        .isInstanceOf(ReservationConflictException.class);
    verify(snapshots, never()).readPatterns(any(PatternContext.class));
  }

  @Test
  void rejectedReplayDoesNotRepublishViolationOrReevaluatePatterns() {
    com.sportsbook.risk.service.LimitRejection rejection =
        new com.sportsbook.risk.service.LimitRejection(
            "STAKE_DAILY_LIMIT_EXCEEDED",
            com.sportsbook.protocol.value.Currency.KRW,
            900L,
            1_000L,
            200L,
            PatternAction.BLOCK.name());
    when(store.reserve(
            any(), nullable(com.sportsbook.risk.service.LimitRejection.class), anyList()))
        .thenReturn(ReservationDecision.rejected(rejection, true));

    RiskReservationOutcome outcome = service.reserve(command());

    assertThat(outcome.approved()).isFalse();
    assertThat(outcome.rejection()).contains(rejection);
    verify(snapshots, never()).readPatterns(any(PatternContext.class));
    verify(publisher, never()).publishLimitViolated(any(), any(), any());
    assertThat(meters.counter("risk_reservation_requests_total", "result", "replay").count())
        .isEqualTo(1.0);
    assertThat(
            meters
                .counter("risk_limit_violations_total", "reason", "STAKE_DAILY_LIMIT_EXCEEDED")
                .count())
        .isZero();
  }

  @Test
  void hundredApprovedReplaysReturnTheCompleteOriginalResponse() {
    PatternMatch flag =
        new PatternMatch("rapid-betting", PatternAction.SUSPECT, "original decision");
    when(store.needsPatternEvaluation(any())).thenReturn(true, false);
    when(snapshots.readPatterns(any())).thenReturn(patternSnapshot);
    when(rules.evaluate(any(), eq(patternSnapshot))).thenReturn(List.of(flag));
    AtomicInteger calls = new AtomicInteger();
    when(store.reserve(
            any(), nullable(com.sportsbook.risk.service.LimitRejection.class), anyList()))
        .thenAnswer(
            ignored ->
                ReservationDecision.approved(
                    ReservationState.RESERVED,
                    EXPIRES,
                    calls.getAndIncrement() > 0,
                    List.of(flag)));

    List<RiskReservationOutcome> outcomes = new ArrayList<>();
    for (int attempt = 0; attempt < 100; attempt++) {
      outcomes.add(service.reserve(command()));
    }

    assertThat(outcomes).hasSize(100).allMatch(outcomes.get(0)::equals);
    verify(snapshots).readPatterns(any(PatternContext.class));
    verify(publisher).publishPatternSuspected("user-1", flag, NOW);
  }

  @Test
  void hundredBlockingReplaysReturnTheCompleteOriginalRejection() {
    PatternMatch block = new PatternMatch("rapid-betting", PatternAction.BLOCK, "blocked");
    com.sportsbook.risk.service.LimitRejection rejection =
        new com.sportsbook.risk.service.LimitRejection(
            "PATTERN_RAPID_BETTING", null, 0L, 0L, 0L, PatternAction.BLOCK.name());
    when(store.needsPatternEvaluation(any())).thenReturn(true, false);
    when(snapshots.readPatterns(any())).thenReturn(patternSnapshot);
    when(rules.evaluate(any(), eq(patternSnapshot))).thenReturn(List.of(block));
    AtomicInteger calls = new AtomicInteger();
    when(store.reserve(any(), any(), anyList()))
        .thenAnswer(
            ignored ->
                ReservationDecision.rejected(
                    rejection, calls.getAndIncrement() > 0, List.of(block)));

    List<RiskReservationOutcome> outcomes = new ArrayList<>();
    for (int attempt = 0; attempt < 100; attempt++) {
      outcomes.add(service.reserve(command()));
    }

    assertThat(outcomes).hasSize(100).allMatch(outcomes.get(0)::equals);
    verify(snapshots).readPatterns(any(PatternContext.class));
    verify(publisher).publishPatternSuspected("user-1", block, NOW);
    assertThat(
            meters
                .counter(
                    "risk_pattern_flags_total",
                    "rule",
                    block.ruleName(),
                    "action",
                    block.action().name())
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void tombstonedCommitPreservesThePublicNotFoundContract() {
    when(store.commit("bet-1", NOW)).thenReturn(ReservationTransition.TOMBSTONED);

    assertThatThrownBy(() -> service.commit("bet-1", NOW))
        .isInstanceOf(ReservationNotFoundException.class);
    assertThat(
            meters
                .counter(
                    "risk_reservation_transitions_total",
                    "operation",
                    "commit",
                    "result",
                    "tombstoned")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void releasedTombstoneReplayRemainsNoContentAndIsMeteredAsReplay() {
    when(store.release("bet-1", NOW)).thenReturn(ReservationTransition.REPLAYED);

    service.release("bet-1", NOW);

    assertThat(
            meters
                .counter(
                    "risk_reservation_transitions_total",
                    "operation",
                    "release",
                    "result",
                    "replay")
                .count())
        .isEqualTo(1.0);
  }

  private static RiskCheckCommand command() {
    return new RiskCheckCommand("user-1", "bet-1", Money.krw(100L), List.of("selection-1"), NOW);
  }
}
