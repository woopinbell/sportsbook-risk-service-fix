package com.sportsbook.risk.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sportsbook.protocol.event.BetPlacedRequested;
import com.sportsbook.protocol.event.BetSlipTypeTag;
import com.sportsbook.protocol.event.Money;
import com.sportsbook.protocol.event.RequestedSelection;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.counter.SlidingWindowCounter;
import com.sportsbook.risk.pattern.UserBetHistoryWriter;
import com.sportsbook.risk.reservation.RedisRiskReservationStore;
import com.sportsbook.risk.reservation.ReservationTransition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class BetPlacedConsumerTest {

  @Mock private SlidingWindowCounter counter;
  @Mock private UserBetHistoryWriter history;
  @Mock private RedisRiskReservationStore reservations;
  @Mock private Acknowledgment acknowledgment;

  private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void recordsStakeAndSelectionCountersPlusHistory() {
    org.mockito.Mockito.when(reservations.commit(any(), any()))
        .thenReturn(ReservationTransition.NOT_FOUND);
    BetPlacedConsumer consumer = new BetPlacedConsumer(counter, history, reservations, CLOCK);
    BetPlacedRequested event =
        BetPlacedRequested.newBuilder()
            .setBetId("b-1")
            .setUserId("u-1")
            .setSlipType(BetSlipTypeTag.MULTIPLE)
            .setSystemMinWins(null)
            .setSystemTotalSelections(null)
            .setSelections(List.of(sel("s-1"), sel("s-2")))
            .setStake(Money.newBuilder().setAmount(10_000L).setCurrency("KRW").build())
            .setIdempotencyKey("idem-1")
            .setRequestedAt(java.time.Instant.ofEpochMilli(1748432400000L))
            .build();

    consumer.onBetPlaced(AvroCodec.encode(event), "u-1", acknowledgment);

    // Three stake-window writes: daily, weekly, monthly.
    verify(counter, times(3))
        .record(any(String.class), any(String.class), eq(10_000L), any(Duration.class), any());
    // One selections-per-minute write (amount=2).
    verify(counter)
        .record(
            any(String.class),
            any(String.class),
            eq(2L),
            eq(LimitType.SELECTIONS_PER_MINUTE.window()),
            any());
    verify(history).recordBet(eq("u-1"), eq("b-1"), eq(10_000L), eq(List.of("s-1", "s-2")), any());
    verify(acknowledgment).acknowledge();
  }

  @Test
  void skipsSelectionCounterWhenSlipHasNoSelections() {
    org.mockito.Mockito.when(reservations.commit(any(), any()))
        .thenReturn(ReservationTransition.NOT_FOUND);
    BetPlacedConsumer consumer = new BetPlacedConsumer(counter, history, reservations, CLOCK);
    BetPlacedRequested event =
        BetPlacedRequested.newBuilder()
            .setBetId("b-1")
            .setUserId("u-1")
            .setSlipType(BetSlipTypeTag.SINGLE)
            .setSystemMinWins(null)
            .setSystemTotalSelections(null)
            .setSelections(List.of())
            .setStake(Money.newBuilder().setAmount(5_000L).setCurrency("KRW").build())
            .setIdempotencyKey("idem-2")
            .setRequestedAt(java.time.Instant.ofEpochMilli(1748432400000L))
            .build();

    consumer.onBetPlaced(AvroCodec.encode(event), "u-1", acknowledgment);

    verify(counter, never())
        .record(
            any(String.class),
            any(String.class),
            anyLong(),
            eq(LimitType.SELECTIONS_PER_MINUTE.window()),
            any());
    verify(history).recordBet(eq("u-1"), eq("b-1"), eq(5_000L), eq(List.of()), any());
    verify(acknowledgment).acknowledge();
  }

  @Test
  void leavesOffsetUncommittedWhenAWriteFails() {
    org.mockito.Mockito.when(reservations.commit(any(), any()))
        .thenReturn(ReservationTransition.NOT_FOUND);
    BetPlacedConsumer consumer = new BetPlacedConsumer(counter, history, reservations, CLOCK);
    BetPlacedRequested event =
        BetPlacedRequested.newBuilder()
            .setBetId("b-1")
            .setUserId("u-1")
            .setSlipType(BetSlipTypeTag.SINGLE)
            .setSystemMinWins(null)
            .setSystemTotalSelections(null)
            .setSelections(List.of(sel("s-1")))
            .setStake(Money.newBuilder().setAmount(5_000L).setCurrency("KRW").build())
            .setIdempotencyKey("idem-3")
            .setRequestedAt(java.time.Instant.ofEpochMilli(1748432400000L))
            .build();
    doThrow(new IllegalStateException("redis unavailable"))
        .when(history)
        .recordBet(any(), any(), anyLong(), any(), any());

    assertThatThrownBy(() -> consumer.onBetPlaced(AvroCodec.encode(event), "u-1", acknowledgment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("redis unavailable");

    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  void committedReservationMakesBetEventAHistoryOnlyIdempotentConfirmation() {
    org.mockito.Mockito.when(reservations.commit(any(), any()))
        .thenReturn(ReservationTransition.REPLAYED);
    BetPlacedConsumer consumer = new BetPlacedConsumer(counter, history, reservations, CLOCK);
    BetPlacedRequested event =
        BetPlacedRequested.newBuilder()
            .setBetId("b-reserved")
            .setUserId("u-1")
            .setSlipType(BetSlipTypeTag.SINGLE)
            .setSystemMinWins(null)
            .setSystemTotalSelections(null)
            .setSelections(List.of(sel("s-1")))
            .setStake(Money.newBuilder().setAmount(5_000L).setCurrency("KRW").build())
            .setIdempotencyKey("idem-reserved")
            .setRequestedAt(NOW)
            .build();

    consumer.onBetPlaced(AvroCodec.encode(event), "u-1", acknowledgment);

    verify(counter, never())
        .record(any(String.class), any(String.class), anyLong(), any(Duration.class), any());
    verify(history).recordBet(eq("u-1"), eq("b-reserved"), eq(5_000L), eq(List.of("s-1")), eq(NOW));
    verify(acknowledgment).acknowledge();
  }

  @Test
  void lifecycleTombstonesNeverFallBackToLegacyCounters() {
    org.mockito.Mockito.when(reservations.commit(any(), any()))
        .thenReturn(ReservationTransition.EXPIRED, ReservationTransition.TOMBSTONED);
    BetPlacedConsumer consumer = new BetPlacedConsumer(counter, history, reservations, CLOCK);
    BetPlacedRequested event =
        BetPlacedRequested.newBuilder()
            .setBetId("b-terminal")
            .setUserId("u-1")
            .setSlipType(BetSlipTypeTag.SINGLE)
            .setSystemMinWins(null)
            .setSystemTotalSelections(null)
            .setSelections(List.of(sel("s-1")))
            .setStake(Money.newBuilder().setAmount(5_000L).setCurrency("KRW").build())
            .setIdempotencyKey("idem-terminal")
            .setRequestedAt(NOW)
            .build();

    consumer.onBetPlaced(AvroCodec.encode(event), "u-1", acknowledgment);
    consumer.onBetPlaced(AvroCodec.encode(event), "u-1", acknowledgment);

    verify(counter, never())
        .record(any(String.class), any(String.class), anyLong(), any(Duration.class), any());
    verify(history, times(2))
        .recordBet(eq("u-1"), eq("b-terminal"), eq(5_000L), eq(List.of("s-1")), eq(NOW));
    verify(acknowledgment, times(2)).acknowledge();
  }

  private static RequestedSelection sel(String selectionId) {
    return RequestedSelection.newBuilder()
        .setEventId("e-1")
        .setMarketId("m-1")
        .setSelectionId(selectionId)
        .setOddsAtSubmission("1.85")
        .build();
  }
}
