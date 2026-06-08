package com.sportsbook.risk.event;

import com.sportsbook.protocol.event.BetPlacedRequested;
import com.sportsbook.protocol.event.RequestedSelection;
import com.sportsbook.risk.counter.LimitKeys;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.counter.SlidingWindowCounter;
import com.sportsbook.risk.pattern.UserBetHistoryWriter;
import com.sportsbook.risk.reservation.RedisRiskReservationStore;
import com.sportsbook.risk.reservation.ReservationTransition;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Confirms the risk-service runtime state from {@code bet.placed}. For every accepted bet:
 *
 * <ul>
 *   <li>Commits an admission reservation, or records the four counters for a legacy publisher that
 *       did not reserve.
 *   <li>Appends to the pattern-rule history via {@link UserBetHistoryWriter}.
 * </ul>
 *
 * <p>Spring Kafka is configured with {@code manual_immediate} ack so we only commit after the Redis
 * transition and history write succeed. A transient Redis blip leaves the offset behind and lets
 * the broker redeliver; both paths are idempotent by bet identifier.
 */
@Component
public class BetPlacedConsumer {

  private static final Logger log = LoggerFactory.getLogger(BetPlacedConsumer.class);
  private static final List<LimitType> STAKE_LIMITS =
      List.of(LimitType.STAKE_DAILY, LimitType.STAKE_WEEKLY, LimitType.STAKE_MONTHLY);

  private final SlidingWindowCounter counter;
  private final UserBetHistoryWriter history;
  private final RedisRiskReservationStore reservations;
  private final Clock clock;

  public BetPlacedConsumer(
      SlidingWindowCounter counter,
      UserBetHistoryWriter history,
      RedisRiskReservationStore reservations,
      Clock clock) {
    this.counter = counter;
    this.history = history;
    this.reservations = reservations;
    this.clock = clock;
  }

  @KafkaListener(
      topics = "${topics.bet-placed}",
      groupId = "${spring.kafka.consumer.group-id}",
      containerFactory = "kafkaListenerContainerFactory")
  public void onBetPlaced(
      @Payload byte[] payload,
      @Header(KafkaHeaders.RECEIVED_KEY) String key,
      Acknowledgment acknowledgment) {
    BetPlacedRequested event = AvroCodec.decode(payload, BetPlacedRequested.class);
    String userId = event.getUserId().toString();
    String betId = event.getBetId().toString();
    long stakeAmount = event.getStake().getAmount();
    Instant now = event.getRequestedAt();
    List<String> selectionIds = event.getSelections().stream().map(this::selectionId).toList();

    ReservationTransition reservation = reservations.commit(betId, clock.instant());
    if (reservation == ReservationTransition.NOT_FOUND) {
      recordLegacyCounters(userId, betId, stakeAmount, selectionIds.size(), now);
    } else if (reservation == ReservationTransition.COMMITTED_CONFLICT) {
      throw new IllegalStateException("Reservation commit returned a release-only transition");
    }
    history.recordBet(userId, betId, stakeAmount, selectionIds, now);
    acknowledgment.acknowledge();

    log.debug(
        "Recorded bet.placed userId={} betId={} stake={} key={}", userId, betId, stakeAmount, key);
  }

  private String selectionId(RequestedSelection s) {
    return s.getSelectionId().toString();
  }

  private void recordLegacyCounters(
      String userId, String betId, long stakeAmount, int selectionCount, Instant now) {
    for (LimitType type : STAKE_LIMITS) {
      counter.record(
          LimitKeys.userKey(userId, type),
          LimitKeys.encodeMember(betId, stakeAmount),
          stakeAmount,
          type.window(),
          now);
    }
    if (selectionCount > 0) {
      counter.record(
          LimitKeys.userKey(userId, LimitType.SELECTIONS_PER_MINUTE),
          LimitKeys.encodeMember(betId, selectionCount),
          selectionCount,
          LimitType.SELECTIONS_PER_MINUTE.window(),
          now);
    }
  }
}
