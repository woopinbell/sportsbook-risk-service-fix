package com.sportsbook.risk.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sportsbook.protocol.event.BetPlacedRequested;
import com.sportsbook.protocol.event.BetSlipTypeTag;
import com.sportsbook.protocol.event.Money;
import com.sportsbook.protocol.event.RequestedSelection;
import com.sportsbook.risk.counter.LimitKeys;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.counter.SlidingWindowCounter;
import com.sportsbook.risk.pattern.RedisUserBetHistory;
import com.sportsbook.risk.reservation.RedisRiskReservationStore;
import com.sportsbook.risk.service.RiskCheckCommand;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@EmbeddedKafka(partitions = 1, topics = "bet.placed.integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "topics.bet-placed=bet.placed.integration",
      "spring.kafka.consumer.group-id=risk.bet-placed-integration",
      "spring.kafka.consumer.auto-offset-reset=earliest",
      "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    })
class BetPlacedConsumerIntegrationTest {

  private static final String TOPIC = "bet.placed.integration";
  private static final String GROUP = "risk.bet-placed-integration";
  private static final Instant OCCURRED_AT = Instant.parse("2026-07-13T03:00:00Z");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired private KafkaTemplate<String, byte[]> kafka;
  @Autowired private EmbeddedKafkaBroker embeddedKafka;
  @Autowired private StringRedisTemplate redis;
  @Autowired private SlidingWindowCounter counter;
  @Autowired private RedisRiskReservationStore reservations;
  @Autowired private BetPlacedConsumer consumer;
  @SpyBean private RedisUserBetHistory history;

  @BeforeEach
  void clearRedis() {
    redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @Test
  void failedDeliveryIsRedeliveredAndConvergesBeforeOffsetCommit() throws Exception {
    AtomicBoolean failFirstHistoryWrite = new AtomicBoolean(true);
    CountDownLatch retryEntered = new CountDownLatch(1);
    CountDownLatch allowRetryToComplete = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              if (failFirstHistoryWrite.compareAndSet(true, false)) {
                throw new IllegalStateException("injected history failure");
              }
              retryEntered.countDown();
              if (!allowRetryToComplete.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the retry");
              }
              return invocation.callRealMethod();
            })
        .when(history)
        .recordBet(any(), any(), any(Long.class), any(), any());

    BetPlacedRequested event = event();
    kafka
        .send(TOPIC, event.getUserId().toString(), AvroCodec.encode(event))
        .get(20, TimeUnit.SECONDS);

    assertThat(retryEntered.await(20, TimeUnit.SECONDS)).isTrue();
    try {
      assertThat(committedOffset()).isLessThan(1L);
    } finally {
      allowRetryToComplete.countDown();
    }

    await(
        Duration.ofSeconds(30),
        () ->
            history.countBetsBetween(
                    event.getUserId().toString(),
                    OCCURRED_AT.minusSeconds(1),
                    OCCURRED_AT.plusSeconds(1))
                == 1L);
    await(Duration.ofSeconds(30), () -> committedOffset() == 1L);

    String userId = event.getUserId().toString();
    assertThat(current(userId, LimitType.STAKE_DAILY)).isEqualTo(12_345L);
    assertThat(current(userId, LimitType.STAKE_WEEKLY)).isEqualTo(12_345L);
    assertThat(current(userId, LimitType.STAKE_MONTHLY)).isEqualTo(12_345L);
    assertThat(current(userId, LimitType.SELECTIONS_PER_MINUTE)).isEqualTo(2L);
    assertThat(
            history.countSelectionBets(
                userId,
                event.getSelections().get(0).getSelectionId().toString(),
                Duration.ofMinutes(1),
                OCCURRED_AT))
        .isEqualTo(1L);
    assertThat(
            history.countSelectionBets(
                userId,
                event.getSelections().get(1).getSelectionId().toString(),
                Duration.ofMinutes(1),
                OCCURRED_AT))
        .isEqualTo(1L);
    assertThat(committedOffset()).isEqualTo(1L);
    verify(history, atLeast(2)).recordBet(any(), any(), any(Long.class), any(), any());
  }

  @Test
  void reservedBetEventCommitsOnceAndRedeliveryDoesNotDoubleCount() {
    Instant now = Instant.now();
    BetPlacedRequested event = event("40000000-0000-4000-8000-000000000002", now);
    RiskCheckCommand command =
        new RiskCheckCommand(
            event.getUserId().toString(),
            event.getBetId().toString(),
            com.sportsbook.protocol.value.Money.krw(event.getStake().getAmount()),
            event.getSelections().stream()
                .map(selection -> selection.getSelectionId().toString())
                .toList(),
            now);
    assertThat(reservations.reserve(command).approved()).isTrue();
    Acknowledgment acknowledgment = mock(Acknowledgment.class);

    consumer.onBetPlaced(AvroCodec.encode(event), event.getUserId().toString(), acknowledgment);
    consumer.onBetPlaced(AvroCodec.encode(event), event.getUserId().toString(), acknowledgment);

    String userId = event.getUserId().toString();
    for (LimitType type :
        List.of(LimitType.STAKE_DAILY, LimitType.STAKE_WEEKLY, LimitType.STAKE_MONTHLY)) {
      assertThat(counter.currentSum(LimitKeys.userKey(userId, type), type.window(), Instant.now()))
          .isEqualTo(12_345L);
      assertThat(redis.opsForZSet().size(LimitKeys.userKey(userId, type))).isEqualTo(1L);
    }
    assertThat(
            counter.currentSum(
                LimitKeys.userKey(userId, LimitType.SELECTIONS_PER_MINUTE),
                LimitType.SELECTIONS_PER_MINUTE.window(),
                Instant.now()))
        .isEqualTo(2L);
    assertThat(history.countBetsBetween(userId, now.minusSeconds(1), now.plusSeconds(1)))
        .isEqualTo(1L);
    verify(acknowledgment, times(2)).acknowledge();
  }

  private long current(String userId, LimitType type) {
    return counter.currentSum(LimitKeys.userKey(userId, type), type.window(), OCCURRED_AT);
  }

  private long committedOffset() {
    try (AdminClient admin =
        AdminClient.create(
            Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString()))) {
      var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get();
      var offset = offsets.get(new TopicPartition(TOPIC, 0));
      return offset == null ? -1L : offset.offset();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while inspecting the committed offset", failure);
    } catch (Exception failure) {
      throw new AssertionError("failed to inspect the committed offset", failure);
    }
  }

  private static void await(Duration timeout, BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) {
        throw new AssertionError("condition did not become true within " + timeout);
      }
      Thread.sleep(100L);
    }
  }

  private static BetPlacedRequested event() {
    return event("40000000-0000-4000-8000-000000000001", OCCURRED_AT);
  }

  private static BetPlacedRequested event(String betId, Instant requestedAt) {
    return BetPlacedRequested.newBuilder()
        .setBetId(betId)
        .setUserId("20000000-0000-4000-8000-000000000001")
        .setSlipType(BetSlipTypeTag.MULTIPLE)
        .setSystemMinWins(null)
        .setSystemTotalSelections(null)
        .setSelections(
            List.of(
                selection("10000000-0000-4000-8000-000000000001"),
                selection("10000000-0000-4000-8000-000000000002")))
        .setStake(Money.newBuilder().setAmount(12_345L).setCurrency("KRW").build())
        .setIdempotencyKey("risk-integration-1")
        .setRequestedAt(requestedAt)
        .build();
  }

  private static RequestedSelection selection(String selectionId) {
    return RequestedSelection.newBuilder()
        .setEventId("50000000-0000-4000-8000-000000000001")
        .setMarketId("60000000-0000-4000-8000-000000000001")
        .setSelectionId(selectionId)
        .setOddsAtSubmission("1.85")
        .build();
  }
}
