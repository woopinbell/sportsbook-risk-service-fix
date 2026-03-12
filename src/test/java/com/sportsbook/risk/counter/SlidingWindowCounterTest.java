package com.sportsbook.risk.counter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class SlidingWindowCounterTest {

  private static final Instant T0 = Instant.parse("2026-05-28T10:00:00Z");
  private static final long STAKE_A = 10_000L;
  private static final long STAKE_B = 5_000L;
  private static final Duration MINUTE = Duration.ofMinutes(1);

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory factory;
  private static StringRedisTemplate template;
  private static SlidingWindowCounter counter;

  @BeforeAll
  static void startInfrastructure() {
    factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    factory.afterPropertiesSet();
    template = new StringRedisTemplate(factory);
    counter = new SlidingWindowCounter(template, Clock.fixed(T0, ZoneOffset.UTC));
  }

  @AfterAll
  static void stopInfrastructure() {
    factory.destroy();
  }

  @BeforeEach
  void flush() {
    template.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @Test
  void recordAndCurrentSumAgreeOnSingleEntry() {
    String key = LimitKeys.userKey(uniqueUser(), LimitType.STAKE_DAILY);

    long afterRecord =
        counter.record(key, LimitKeys.encodeMember("bet-1", STAKE_A), STAKE_A, MINUTE, T0);
    long peeked = counter.currentSum(key, MINUTE, T0);

    assertThat(afterRecord).isEqualTo(STAKE_A);
    assertThat(peeked).isEqualTo(STAKE_A);
  }

  @Test
  void multipleRecordsAccumulateWithinWindow() {
    String key = LimitKeys.userKey(uniqueUser(), LimitType.STAKE_DAILY);

    counter.record(key, LimitKeys.encodeMember("bet-1", STAKE_A), STAKE_A, MINUTE, T0);
    long after =
        counter.record(
            key, LimitKeys.encodeMember("bet-2", STAKE_B), STAKE_B, MINUTE, T0.plusSeconds(30));

    assertThat(after).isEqualTo(STAKE_A + STAKE_B);
  }

  @Test
  void expiredEntriesDropOutOfTheSum() {
    String key = LimitKeys.userKey(uniqueUser(), LimitType.STAKE_DAILY);

    counter.record(key, LimitKeys.encodeMember("bet-1", STAKE_A), STAKE_A, MINUTE, T0);
    counter.record(
        key, LimitKeys.encodeMember("bet-2", STAKE_B), STAKE_B, MINUTE, T0.plusSeconds(30));

    // After 61 seconds bet-1 (scored at T0) is outside the 60s window.
    long afterFirstExpiry = counter.currentSum(key, MINUTE, T0.plusSeconds(61));
    assertThat(afterFirstExpiry).isEqualTo(STAKE_B);

    // After 91 seconds bet-2 (scored at T0+30) is also outside.
    long afterSecondExpiry = counter.currentSum(key, MINUTE, T0.plusSeconds(91));
    assertThat(afterSecondExpiry).isZero();
  }

  @Test
  void countStyleLimitUsesAmountOne() {
    String key = LimitKeys.userKey(uniqueUser(), LimitType.SELECTIONS_PER_MINUTE);

    counter.record(key, LimitKeys.encodeMember("sel-1", 1L), 1L, MINUTE, T0);
    counter.record(key, LimitKeys.encodeMember("sel-2", 1L), 1L, MINUTE, T0.plusSeconds(10));
    long afterThird =
        counter.record(key, LimitKeys.encodeMember("sel-3", 1L), 1L, MINUTE, T0.plusSeconds(20));

    assertThat(afterThird).isEqualTo(3);
  }

  @Test
  void recordRejectsNonPositiveAmount() {
    assertThatThrownBy(
            () ->
                counter.record(
                    LimitKeys.userKey("u", LimitType.STAKE_DAILY), "m|0", 0L, MINUTE, T0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hundredConcurrentRecordsLandExactSum() throws Exception {
    String userId = uniqueUser();
    String key = LimitKeys.userKey(userId, LimitType.STAKE_DAILY);
    int threads = 100;
    long perBet = 7_777L;

    // One worker per task — the ready/start/done choreography would deadlock with a smaller
    // pool because every task parks on start.await() before yielding back to the pool.
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger failures = new AtomicInteger();

    try {
      for (int i = 0; i < threads; i++) {
        final int idx = i;
        pool.submit(
            () -> {
              ready.countDown();
              try {
                start.await();
                counter.record(
                    key,
                    LimitKeys.encodeMember("bet-" + idx, perBet),
                    perBet,
                    Duration.ofHours(1),
                    T0);
              } catch (Exception e) {
                failures.incrementAndGet();
              } finally {
                done.countDown();
              }
            });
      }
      ready.await();
      start.countDown();
      assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(failures.get()).isZero();
    long total = counter.currentSum(key, Duration.ofHours(1), T0);
    assertThat(total).isEqualTo((long) threads * perBet);
  }

  private static String uniqueUser() {
    return "u-" + UUID.randomUUID();
  }
}
