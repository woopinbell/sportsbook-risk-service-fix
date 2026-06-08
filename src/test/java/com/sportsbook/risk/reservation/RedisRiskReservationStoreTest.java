package com.sportsbook.risk.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.risk.counter.LimitKeys;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.counter.SlidingWindowCounter;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.policy.PatternAction;
import com.sportsbook.risk.policy.RiskLimitProperties;
import com.sportsbook.risk.service.LimitRejection;
import com.sportsbook.risk.service.RiskCheckCommand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
class RedisRiskReservationStoreTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final String USER = "reservation-user";

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory factory;
  private static StringRedisTemplate redis;
  private static SlidingWindowCounter counters;

  private RedisRiskReservationStore store;
  private SimpleMeterRegistry meters;

  @BeforeAll
  static void startInfrastructure() {
    factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    factory.afterPropertiesSet();
    redis = new StringRedisTemplate(factory);
    counters = new SlidingWindowCounter(redis, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterAll
  static void stopInfrastructure() {
    factory.destroy();
  }

  @BeforeEach
  void setUp() {
    redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
    meters = new SimpleMeterRegistry();
    store =
        new RedisRiskReservationStore(
            redis,
            policy(1_000L),
            new RiskReservationProperties(Duration.ofMinutes(2), Duration.ofDays(32)),
            new ObjectMapper(),
            meters);
  }

  @Test
  void exactlyOneConcurrentCandidateWinsTheLastAvailableCapacity() throws Exception {
    counters.record(
        LimitKeys.userKey(USER, LimitType.STAKE_DAILY),
        LimitKeys.encodeMember("existing", 900L),
        900L,
        LimitType.STAKE_DAILY.window(),
        NOW);

    int workers = 20;
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(workers);
    AtomicInteger approved = new AtomicInteger();
    List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
    try {
      for (int i = 0; i < workers; i++) {
        String betId = "concurrent-" + i;
        pool.submit(
            () -> {
              ready.countDown();
              try {
                start.await();
                if (store.reserve(command(betId, 100L, NOW)).approved()) {
                  approved.incrementAndGet();
                }
              } catch (Throwable failure) {
                failures.add(failure);
              } finally {
                done.countDown();
              }
            });
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(failures).isEmpty();
    assertThat(approved).hasValue(1);
    assertThat(store.activeCount()).isEqualTo(1L);
    assertThat(redis.opsForValue().get(ReservationKeys.reservedStakeSum(USER))).isEqualTo("100");
  }

  @Test
  void hundredSameFingerprintReplaysCreateOneReservationAndReturnOriginalPatterns() {
    List<ReservationDecision> decisions = new ArrayList<>();
    PatternMatch flag =
        new PatternMatch("rapid-betting", PatternAction.SUSPECT, "original decision");

    for (int attempt = 0; attempt < 100; attempt++) {
      decisions.add(
          store.reserve(
              command("same-bet", 100L, NOW.plusMillis(attempt)),
              null,
              attempt == 0 ? List.of(flag) : List.of()));
    }

    assertThat(decisions).allMatch(ReservationDecision::approved);
    assertThat(decisions.stream().filter(ReservationDecision::replayed).count()).isEqualTo(99L);
    assertThat(decisions)
        .allSatisfy(decision -> assertThat(decision.patternsFlagged()).containsExactly(flag));
    assertThat(redis.opsForZSet().size(ReservationKeys.userReservations(USER))).isEqualTo(1L);
    assertThat(redis.opsForValue().get(ReservationKeys.reservedStakeSum(USER))).isEqualTo("100");
    assertThat(store.activeCount()).isEqualTo(1L);
    assertThat(redis.getExpire(ReservationKeys.userReservations(USER))).isEqualTo(-1L);
    assertThat(redis.getExpire(ReservationKeys.reservedStakeSum(USER))).isEqualTo(-1L);
    assertThat(redis.getExpire(ReservationKeys.reservedSelectionSum(USER))).isEqualTo(-1L);
  }

  @Test
  void sameBetWithChangedPayloadConflicts() {
    assertThat(store.reserve(command("changed", 100L, NOW)).approved()).isTrue();

    ReservationDecision conflict = store.reserve(command("changed", 101L, NOW.plusSeconds(1)));

    assertThat(conflict.status()).isEqualTo(ReservationDecision.Status.CONFLICT);
    assertThat(redis.opsForValue().get(ReservationKeys.reservedStakeSum(USER))).isEqualTo("100");
  }

  @Test
  void nonPositiveStakeCannotCreateAReservationOrCorruptActiveTotals() {
    assertThatThrownBy(
            () ->
                new RiskCheckCommand(
                    USER, "negative", Money.krw(-100L), List.of("selection-1"), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("stake amount must be positive");

    assertThat(redis.hasKey(ReservationKeys.reservation("negative"))).isFalse();
    assertThat(redis.hasKey(ReservationKeys.userReservations(USER))).isFalse();
    assertThat(redis.hasKey(ReservationKeys.reservedStakeSum(USER))).isFalse();
    assertThat(store.activeCount()).isZero();
  }

  @Test
  void rejectedDecisionReplaysAfterLimitChangeAndChangedFingerprintConflicts() {
    store =
        new RedisRiskReservationStore(
            redis,
            policy(100L),
            new RiskReservationProperties(Duration.ofMinutes(2), Duration.ofDays(1)),
            new ObjectMapper(),
            meters);
    RiskCheckCommand original = command("rejected", 101L, NOW);

    ReservationDecision first = store.reserve(original);
    redis.opsForHash().put("limit:override:user:" + USER, "STAKE_DAILY:KRW", "1000");
    List<ReservationDecision> replays = new ArrayList<>();
    for (int attempt = 1; attempt < 100; attempt++) {
      replays.add(store.reserve(command("rejected", 101L, NOW.plusMillis(attempt))));
    }
    ReservationDecision conflict = store.reserve(command("rejected", 102L, NOW.plusSeconds(2)));

    assertThat(first.status()).isEqualTo(ReservationDecision.Status.REJECTED);
    assertThat(first.rejection().reason()).isEqualTo("STAKE_DAILY_LIMIT_EXCEEDED");
    assertThat(first.replayed()).isFalse();
    assertThat(replays).hasSize(99);
    assertThat(replays)
        .allSatisfy(
            replay -> {
              assertThat(replay.status()).isEqualTo(ReservationDecision.Status.REJECTED);
              assertThat(replay.rejection()).isEqualTo(first.rejection());
              assertThat(replay.replayed()).isTrue();
            });
    assertThat(conflict.status()).isEqualTo(ReservationDecision.Status.CONFLICT);
    assertThat(state("rejected")).isEqualTo("REJECTED");
    assertRetention("rejected", Duration.ofDays(1));
    assertThat(store.activeCount()).isZero();
  }

  @Test
  void patternBlockIsWrittenAsRejectedWithoutExposingAnActiveReservation() {
    LimitRejection patternBlock =
        new LimitRejection("PATTERN_RAPID_BETTING", null, 0L, 0L, 0L, PatternAction.BLOCK.name());
    PatternMatch block =
        new PatternMatch("rapid-betting", PatternAction.BLOCK, "original block decision");

    ReservationDecision first =
        store.reserve(command("pattern-rejected", 100L, NOW), patternBlock, List.of(block));
    List<ReservationDecision> replays = new ArrayList<>();
    for (int attempt = 1; attempt < 100; attempt++) {
      replays.add(
          store.reserve(
              command("pattern-rejected", 100L, NOW.plusMillis(attempt)), null, List.of()));
    }
    ReservationDecision conflict =
        store.reserve(
            command("pattern-rejected", 101L, NOW.plusSeconds(1)), patternBlock, List.of(block));

    assertThat(first.status()).isEqualTo(ReservationDecision.Status.REJECTED);
    assertThat(first.rejection()).isEqualTo(patternBlock);
    assertThat(first.patternsFlagged()).containsExactly(block);
    assertThat(first.replayed()).isFalse();
    assertThat(replays)
        .allSatisfy(
            replay -> {
              assertThat(replay.status()).isEqualTo(ReservationDecision.Status.REJECTED);
              assertThat(replay.rejection()).isEqualTo(patternBlock);
              assertThat(replay.patternsFlagged()).containsExactly(block);
              assertThat(replay.replayed()).isTrue();
            });
    assertThat(conflict.status()).isEqualTo(ReservationDecision.Status.CONFLICT);
    assertThat(state("pattern-rejected")).isEqualTo("REJECTED");
    assertThat(redis.hasKey(ReservationKeys.userReservations(USER))).isFalse();
    assertThat(redis.hasKey(ReservationKeys.reservedStakeSum(USER))).isFalse();
    assertThat(store.activeCount()).isZero();
  }

  @Test
  void releasedTombstoneDoesNotReactivateAndChangedFingerprintConflicts() {
    assertThat(store.reserve(command("first", 1_000L, NOW)).approved()).isTrue();
    assertThat(store.reserve(command("blocked", 1L, NOW)).approved()).isFalse();

    assertThat(store.release("first", NOW.plusSeconds(1))).isEqualTo(ReservationTransition.APPLIED);
    assertThat(store.release("first", NOW.plusSeconds(2)))
        .isEqualTo(ReservationTransition.REPLAYED);

    ReservationDecision same = store.reserve(command("first", 1_000L, NOW.plusSeconds(3)));
    ReservationDecision changed = store.reserve(command("first", 999L, NOW.plusSeconds(4)));

    assertThat(same.status()).isEqualTo(ReservationDecision.Status.REJECTED);
    assertThat(same.rejection().reason()).isEqualTo("RISK_RESERVATION_RELEASED");
    assertThat(same.replayed()).isTrue();
    assertThat(changed.status()).isEqualTo(ReservationDecision.Status.CONFLICT);
    assertThat(state("first")).isEqualTo("RELEASED");
    assertRetention("first", Duration.ofDays(32));
    assertThat(store.reserve(command("second", 1_000L, NOW.plusSeconds(1))).approved()).isTrue();
    assertThat(store.activeCount()).isEqualTo(1L);
  }

  @Test
  void expiredLeaseIsCleanedAndCapacityCanBeReservedAgain() {
    store =
        new RedisRiskReservationStore(
            redis,
            policy(1_000L),
            new RiskReservationProperties(Duration.ofMillis(100), Duration.ofDays(1)),
            new ObjectMapper(),
            meters);
    assertThat(store.reserve(command("expired", 1_000L, NOW)).approved()).isTrue();

    ReservationDecision replacement =
        store.reserve(command("replacement", 1_000L, NOW.plusMillis(101)));

    assertThat(replacement.approved()).isTrue();
    assertThat(state("expired")).isEqualTo("EXPIRED");
    assertRetention("expired", Duration.ofDays(1));
    assertThat(store.activeCount()).isEqualTo(1L);
  }

  @Test
  void expiredTombstoneConflictsOnChangedFingerprintAndAllowsSameFingerprintRereservation() {
    store =
        new RedisRiskReservationStore(
            redis,
            policy(1_000L),
            new RiskReservationProperties(Duration.ofMillis(100), Duration.ofDays(1)),
            new ObjectMapper(),
            meters);
    assertThat(store.reserve(command("expired-retry", 100L, NOW)).approved()).isTrue();

    assertThat(store.commit("expired-retry", NOW.plusMillis(101)))
        .isEqualTo(ReservationTransition.EXPIRED);
    assertThat(state("expired-retry")).isEqualTo("EXPIRED");
    assertRetention("expired-retry", Duration.ofDays(1));

    ReservationDecision conflict =
        store.reserve(command("expired-retry", 101L, NOW.plusMillis(102)));
    ReservationDecision retried =
        store.reserve(command("expired-retry", 100L, NOW.plusMillis(103)));

    assertThat(conflict.status()).isEqualTo(ReservationDecision.Status.CONFLICT);
    assertThat(retried.approved()).isTrue();
    assertThat(retried.replayed()).isFalse();
    assertThat(retried.state()).isEqualTo(ReservationState.RESERVED);
    assertThat(store.activeCount()).isEqualTo(1L);
    assertThat(redis.opsForValue().get(ReservationKeys.reservedStakeSum(USER))).isEqualTo("100");
    assertThat(meters.counter("risk_reservation_expirations_total").count()).isEqualTo(1.0);
  }

  @Test
  void explicitReleaseAfterLeaseTimeCreatesReleasedTombstone() {
    store =
        new RedisRiskReservationStore(
            redis,
            policy(1_000L),
            new RiskReservationProperties(Duration.ofMillis(100), Duration.ofDays(1)),
            new ObjectMapper(),
            meters);
    assertThat(store.reserve(command("release-expired", 100L, NOW)).approved()).isTrue();

    assertThat(store.release("release-expired", NOW.plusMillis(101)))
        .isEqualTo(ReservationTransition.APPLIED);
    assertThat(store.release("release-expired", NOW.plusMillis(102)))
        .isEqualTo(ReservationTransition.REPLAYED);

    assertThat(state("release-expired")).isEqualTo("RELEASED");
    assertThat(store.activeCount()).isZero();
    assertThat(redis.hasKey(ReservationKeys.reservedStakeSum(USER))).isFalse();
    assertThat(meters.counter("risk_reservation_expirations_total").count()).isZero();
  }

  @Test
  void terminalCommitNeverCreatesUsageAndOnlyTrueMissingUsesLegacyMarker() {
    store =
        new RedisRiskReservationStore(
            redis,
            policy(100L),
            new RiskReservationProperties(Duration.ofMinutes(2), Duration.ofDays(1)),
            new ObjectMapper(),
            meters);
    assertThat(store.reserve(command("terminal-rejected", 101L, NOW)).approved()).isFalse();
    assertThat(store.commit("terminal-rejected", NOW.plusSeconds(1)))
        .isEqualTo(ReservationTransition.TOMBSTONED);

    assertThat(store.reserve(command("terminal-released", 100L, NOW)).approved()).isTrue();
    assertThat(store.release("terminal-released", NOW.plusSeconds(1)))
        .isEqualTo(ReservationTransition.APPLIED);
    assertThat(store.commit("terminal-released", NOW.plusSeconds(2)))
        .isEqualTo(ReservationTransition.TOMBSTONED);
    assertThat(store.commit("never-seen", NOW.plusSeconds(2)))
        .isEqualTo(ReservationTransition.NOT_FOUND);

    for (LimitType type : LimitType.values()) {
      assertThat(
              counters.currentSum(LimitKeys.userKey(USER, type), type.window(), NOW.plusSeconds(2)))
          .isZero();
    }
  }

  @Test
  void betIdCanBeReusedOnlyAfterTheTombstoneTtlExpires() throws Exception {
    assertThat(store.reserve(command("ttl-reuse", 100L, NOW)).approved()).isTrue();
    assertThat(store.release("ttl-reuse", NOW.plusSeconds(1)))
        .isEqualTo(ReservationTransition.APPLIED);
    assertThat(store.reserve(command("ttl-reuse", 101L, NOW.plusSeconds(2))).status())
        .isEqualTo(ReservationDecision.Status.CONFLICT);

    String lifecycleKey = ReservationKeys.reservation("ttl-reuse");
    redis.expire(lifecycleKey, Duration.ofMillis(1));
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (Boolean.TRUE.equals(redis.hasKey(lifecycleKey)) && System.nanoTime() < deadline) {
      Thread.sleep(5L);
    }

    assertThat(redis.hasKey(lifecycleKey)).isFalse();
    assertThat(store.reserve(command("ttl-reuse", 101L, NOW.plusSeconds(3))).approved()).isTrue();
  }

  @Test
  void commitMovesReservationToCommittedCountersExactlyOnce() {
    assertThat(store.reserve(command("committed", 250L, NOW)).approved()).isTrue();

    assertThat(store.commit("committed", NOW.plusSeconds(1)))
        .isEqualTo(ReservationTransition.APPLIED);
    assertThat(store.commit("committed", NOW.plusSeconds(2)))
        .isEqualTo(ReservationTransition.REPLAYED);

    for (LimitType type :
        List.of(LimitType.STAKE_DAILY, LimitType.STAKE_WEEKLY, LimitType.STAKE_MONTHLY)) {
      assertThat(
              counters.currentSum(LimitKeys.userKey(USER, type), type.window(), NOW.plusSeconds(2)))
          .isEqualTo(250L);
      assertThat(redis.opsForZSet().size(LimitKeys.userKey(USER, type))).isEqualTo(1L);
    }
    assertThat(
            counters.currentSum(
                LimitKeys.userKey(USER, LimitType.SELECTIONS_PER_MINUTE),
                LimitType.SELECTIONS_PER_MINUTE.window(),
                NOW.plusSeconds(2)))
        .isEqualTo(1L);
    assertThat(redis.hasKey(ReservationKeys.reservedStakeSum(USER))).isFalse();
    assertThat(store.activeCount()).isZero();
    assertThat(store.release("committed", NOW.plusSeconds(3)))
        .isEqualTo(ReservationTransition.COMMITTED_CONFLICT);
  }

  @Test
  void missingOrExpiredCommitDoesNotCreateCommittedUsage() {
    assertThat(store.commit("missing", NOW)).isEqualTo(ReservationTransition.NOT_FOUND);
    store =
        new RedisRiskReservationStore(
            redis,
            policy(1_000L),
            new RiskReservationProperties(Duration.ofMillis(100), Duration.ofDays(1)),
            new ObjectMapper(),
            meters);
    store.reserve(command("late", 100L, NOW));

    assertThat(store.commit("late", NOW.plusMillis(101))).isEqualTo(ReservationTransition.EXPIRED);
    assertThat(store.commit("late", NOW.plusMillis(102)))
        .isEqualTo(ReservationTransition.TOMBSTONED);
    assertThat(
            counters.currentSum(
                LimitKeys.userKey(USER, LimitType.STAKE_DAILY),
                LimitType.STAKE_DAILY.window(),
                NOW.plusMillis(101)))
        .isZero();
    assertThat(store.activeCount()).isZero();
    assertThat(state("late")).isEqualTo("EXPIRED");
    assertThat(meters.counter("risk_reservation_expirations_total").count()).isEqualTo(1.0);
  }

  private static String state(String betId) {
    Object value = redis.opsForHash().get(ReservationKeys.reservation(betId), "state");
    return value == null ? null : value.toString();
  }

  private static void assertRetention(String betId, Duration retention) {
    Long ttl = redis.getExpire(ReservationKeys.reservation(betId), TimeUnit.MILLISECONDS);
    assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(retention.toMillis());
  }

  private static RiskCheckCommand command(String betId, long stake, Instant now) {
    return new RiskCheckCommand(USER, betId, Money.krw(stake), List.of("selection-1"), now);
  }

  private static RiskLimitProperties policy(long daily) {
    return new RiskLimitProperties(
        Map.of(Currency.KRW, daily),
        Map.of(Currency.KRW, 10_000L),
        Map.of(Currency.KRW, 10_000L),
        Map.of(Currency.KRW, 10_000L),
        Map.of(Currency.KRW, 10_000L),
        100);
  }
}
