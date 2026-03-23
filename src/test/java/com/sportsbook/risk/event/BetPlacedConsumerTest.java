package com.sportsbook.risk.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BetPlacedConsumerTest {

  @Mock private SlidingWindowCounter counter;
  @Mock private UserBetHistoryWriter history;

  @Test
  void recordsStakeAndSelectionCountersPlusHistory() {
    BetPlacedConsumer consumer = new BetPlacedConsumer(counter, history);
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

    consumer.onBetPlaced(AvroCodec.encode(event), "u-1");

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
  }

  @Test
  void skipsSelectionCounterWhenSlipHasNoSelections() {
    BetPlacedConsumer consumer = new BetPlacedConsumer(counter, history);
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

    consumer.onBetPlaced(AvroCodec.encode(event), "u-1");

    verify(counter, never())
        .record(
            any(String.class),
            any(String.class),
            anyLong(),
            eq(LimitType.SELECTIONS_PER_MINUTE.window()),
            any());
    verify(history).recordBet(eq("u-1"), eq("b-1"), eq(5_000L), eq(List.of()), any());
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
