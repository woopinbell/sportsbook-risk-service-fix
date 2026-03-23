package com.sportsbook.risk.event;

import com.sportsbook.protocol.event.BetPlacedRequested;
import com.sportsbook.protocol.event.RequestedSelection;
import com.sportsbook.risk.counter.LimitKeys;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.counter.SlidingWindowCounter;
import com.sportsbook.risk.pattern.UserBetHistoryWriter;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Event-sources the risk-service runtime state off {@code bet.placed}. For every accepted bet:
 *
 * <ul>
 *   <li>Increments the three sliding-window stake counters and the per-minute selection count via
 *       {@link SlidingWindowCounter}.
 *   <li>Appends to the pattern-rule history via {@link UserBetHistoryWriter}.
 * </ul>
 *
 * <p>The risk decision itself runs synchronously in {@link com.sportsbook.risk.service
 * .RiskCheckService} — this consumer is the write side of that read service's state. Spring Kafka
 * is configured with {@code manual_immediate} ack so we only commit after the writes succeed, which
 * means a transient Redis blip leaves the offset behind and lets the broker redeliver.
 */
@Component
public class BetPlacedConsumer {

  private static final Logger log = LoggerFactory.getLogger(BetPlacedConsumer.class);
  private static final List<LimitType> STAKE_LIMITS =
      List.of(LimitType.STAKE_DAILY, LimitType.STAKE_WEEKLY, LimitType.STAKE_MONTHLY);

  private final SlidingWindowCounter counter;
  private final UserBetHistoryWriter history;

  public BetPlacedConsumer(SlidingWindowCounter counter, UserBetHistoryWriter history) {
    this.counter = counter;
    this.history = history;
  }

  @KafkaListener(
      topics = "${topics.bet-placed}",
      groupId = "${spring.kafka.consumer.group-id}",
      containerFactory = "kafkaListenerContainerFactory")
  public void onBetPlaced(@Payload byte[] payload, @Header(KafkaHeaders.RECEIVED_KEY) String key) {
    BetPlacedRequested event = AvroCodec.decode(payload, BetPlacedRequested.class);
    String userId = event.getUserId().toString();
    String betId = event.getBetId().toString();
    long stakeAmount = event.getStake().getAmount();
    Instant now = event.getRequestedAt();
    List<String> selectionIds = event.getSelections().stream().map(this::selectionId).toList();

    for (LimitType type : STAKE_LIMITS) {
      counter.record(
          LimitKeys.userKey(userId, type),
          LimitKeys.encodeMember(betId, stakeAmount),
          stakeAmount,
          type.window(),
          now);
    }
    int selectionCount = selectionIds.size();
    if (selectionCount > 0) {
      counter.record(
          LimitKeys.userKey(userId, LimitType.SELECTIONS_PER_MINUTE),
          LimitKeys.encodeMember(betId, selectionCount),
          selectionCount,
          LimitType.SELECTIONS_PER_MINUTE.window(),
          now);
    }
    history.recordBet(userId, betId, stakeAmount, selectionIds, now);

    log.debug(
        "Recorded bet.placed userId={} betId={} stake={} key={}", userId, betId, stakeAmount, key);
  }

  private String selectionId(RequestedSelection s) {
    return s.getSelectionId().toString();
  }
}
