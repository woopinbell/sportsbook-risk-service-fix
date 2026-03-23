package com.sportsbook.risk.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.sportsbook.protocol.event.BetPlacedRequested;
import com.sportsbook.protocol.event.BetSlipTypeTag;
import com.sportsbook.protocol.event.Money;
import com.sportsbook.protocol.event.RequestedSelection;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvroCodecTest {

  @Test
  void roundTripsBetPlacedRequested() {
    BetPlacedRequested original =
        BetPlacedRequested.newBuilder()
            .setBetId("b-1")
            .setUserId("u-1")
            .setSlipType(BetSlipTypeTag.SINGLE)
            .setSystemMinWins(null)
            .setSystemTotalSelections(null)
            .setSelections(
                List.of(
                    RequestedSelection.newBuilder()
                        .setEventId("e-1")
                        .setMarketId("m-1")
                        .setSelectionId("s-1")
                        .setOddsAtSubmission("1.85")
                        .build()))
            .setStake(Money.newBuilder().setAmount(10_000L).setCurrency("KRW").build())
            .setIdempotencyKey("idem-1")
            .setRequestedAt(Instant.ofEpochMilli(1748432400000L))
            .build();

    byte[] bytes = AvroCodec.encode(original);
    BetPlacedRequested decoded = AvroCodec.decode(bytes, BetPlacedRequested.class);

    assertThat(decoded.getBetId()).hasToString("b-1");
    assertThat(decoded.getUserId()).hasToString("u-1");
    assertThat(decoded.getStake().getAmount()).isEqualTo(10_000L);
    assertThat(decoded.getStake().getCurrency()).hasToString("KRW");
    assertThat(decoded.getSelections()).hasSize(1);
    assertThat(decoded.getSelections().get(0).getSelectionId()).hasToString("s-1");
    assertThat(decoded.getRequestedAt()).isEqualTo(Instant.ofEpochMilli(1748432400000L));
  }
}
