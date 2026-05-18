package com.sportsbook.risk.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.risk.counter.LimitKeys;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.pattern.PatternContext;
import com.sportsbook.risk.pattern.RedisUserBetHistory;
import com.sportsbook.risk.policy.PatternAction;
import com.sportsbook.risk.policy.RiskPatternProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisRiskSnapshotReaderTest {

  private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");
  private static final String USER = "snapshot-user";

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory factory;
  private static StringRedisTemplate template;
  private static RedisRiskSnapshotReader reader;
  private static RedisUserBetHistory history;

  @BeforeAll
  static void startInfrastructure() {
    factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    factory.afterPropertiesSet();
    template = new StringRedisTemplate(factory);
    reader = new RedisRiskSnapshotReader(template, enabledPatterns(), new ObjectMapper());
    history = new RedisUserBetHistory(template);
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
  void emptySnapshotsReturnZerosWithoutCreatingKeys() {
    PatternContext context = context(List.of("s-1"));
    RiskSnapshot snapshot = reader.read(USER, Currency.KRW, context);
    LimitSnapshot limits = snapshot.limits();
    PatternSnapshot patterns = snapshot.patterns();

    for (LimitType type : LimitType.values()) {
      assertThat(limits.current(type)).isZero();
      assertThat(limits.override(type)).isEmpty();
    }
    assertThat(patterns.recentBetCount()).isZero();
    assertThat(patterns.recentStakeAmounts()).isEmpty();
    assertThat(patterns.selectionBetCount("s-1")).isZero();
    assertThat(template.keys("*")).isEmpty();
  }

  @Test
  void limitSnapshotCleansExpiredEntriesRepairsOrphansAndRefreshesTtl() {
    String daily = LimitKeys.userKey(USER, LimitType.STAKE_DAILY);
    template
        .opsForZSet()
        .add(
            daily,
            LimitKeys.encodeMember("expired", 10_000L),
            NOW.minus(Duration.ofDays(2)).toEpochMilli());
    template
        .opsForZSet()
        .add(daily, LimitKeys.encodeMember("current", 20_000L), NOW.toEpochMilli());
    template.opsForValue().set(LimitKeys.sumKey(daily), "30000");

    String weekly = LimitKeys.userKey(USER, LimitType.STAKE_WEEKLY);
    template.opsForValue().set(LimitKeys.sumKey(weekly), "99999");

    String monthly = LimitKeys.userKey(USER, LimitType.STAKE_MONTHLY);
    template
        .opsForZSet()
        .add(monthly, LimitKeys.encodeMember("current", 30_000L), NOW.toEpochMilli());
    String selections = LimitKeys.userKey(USER, LimitType.SELECTIONS_PER_MINUTE);
    template
        .opsForZSet()
        .add(
            selections,
            LimitKeys.encodeMember("fully-expired", 1L),
            NOW.minusSeconds(61).toEpochMilli());
    template.opsForValue().set(LimitKeys.sumKey(selections), "1");
    template.opsForHash().put("limit:override:user:" + USER, "STAKE_DAILY:KRW", "25000");

    LimitSnapshot snapshot = reader.readLimits(USER, Currency.KRW, NOW);

    assertThat(snapshot.current(LimitType.STAKE_DAILY)).isEqualTo(20_000L);
    assertThat(snapshot.override(LimitType.STAKE_DAILY)).contains(25_000L);
    assertThat(template.opsForZSet().size(daily)).isEqualTo(1L);
    assertThat(template.opsForValue().get(LimitKeys.sumKey(daily))).isEqualTo("20000");
    assertThat(template.getExpire(daily)).isBetween(172_798L, 172_800L);

    assertThat(snapshot.current(LimitType.STAKE_WEEKLY)).isZero();
    assertThat(template.hasKey(LimitKeys.sumKey(weekly))).isFalse();

    assertThat(snapshot.current(LimitType.STAKE_MONTHLY)).isZero();
    assertThat(template.opsForZSet().size(monthly)).isEqualTo(1L);
    assertThat(template.hasKey(LimitKeys.sumKey(monthly))).isFalse();
    assertThat(template.getExpire(monthly)).isBetween(5_183_998L, 5_184_000L);

    assertThat(snapshot.current(LimitType.SELECTIONS_PER_MINUTE)).isZero();
    assertThat(template.hasKey(selections)).isFalse();
    assertThat(template.hasKey(LimitKeys.sumKey(selections))).isFalse();
  }

  @Test
  void approvedReadPathUsesExactlyOneSteadyStateEvalshaCall() throws Exception {
    PatternContext context = context(List.of("s-1"));
    reader.read(USER, Currency.KRW, context);
    long before = commandCalls("evalsha");

    reader.read(USER, Currency.KRW, context);

    assertThat(commandCalls("evalsha")).isEqualTo(before + 1L);
  }

  @Test
  void combinedSnapshotPreservesEveryExistingLimitAndPatternFact() {
    writeState(10L, 100L);
    writePatternState();
    PatternContext context = context(List.of("s-1", "s-2"));

    RiskSnapshot snapshot = reader.read(USER, Currency.KRW, context);

    for (LimitType type : LimitType.values()) {
      assertThat(snapshot.limits().current(type)).isEqualTo(10L);
      assertThat(snapshot.limits().override(type)).contains(100L);
    }
    assertThat(observe(snapshot.patterns()))
        .isEqualTo(new PatternObservation(1L, List.of(1_234L), 1L, 1L));
  }

  @Test
  void wrongTypeIsTaggedAndDeferredUntilJavaReachesItsSlot() {
    String weekly = LimitKeys.userKey(USER, LimitType.STAKE_WEEKLY);
    template.opsForValue().set(weekly, "not-a-zset");
    String bets = "history:user:" + USER + ":bets";
    template.opsForValue().set(bets, "not-a-zset");

    RiskSnapshot snapshot = reader.read(USER, Currency.KRW, context(List.of("s-1")));
    LimitSnapshot limits = snapshot.limits();
    PatternSnapshot patterns = snapshot.patterns();

    assertThat(limits.current(LimitType.STAKE_DAILY)).isZero();
    assertThat(limits.override(LimitType.STAKE_DAILY)).isEmpty();
    assertThatThrownBy(() -> limits.current(LimitType.STAKE_WEEKLY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("STAKE_WEEKLY:counter")
        .hasMessageContaining("WRONGTYPE");
    assertThatThrownBy(patterns::recentBetCount)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("rapid-betting")
        .hasMessageContaining("WRONGTYPE");
    assertThat(patterns.selectionBetCount("s-1")).isZero();
  }

  @Test
  void sumOverrideAndSelectionWrongTypesStayDeferredInDecisionOrder() {
    String daily = LimitKeys.userKey(USER, LimitType.STAKE_DAILY);
    template.opsForHash().put(LimitKeys.sumKey(daily), "wrong", "type");
    template.opsForValue().set("limit:override:user:" + USER, "not-a-hash");
    template.opsForValue().set("history:user:" + USER + ":sel:s-1", "not-a-zset");

    LimitSnapshot limits = reader.readLimits(USER, Currency.KRW, NOW);
    PatternSnapshot patterns = reader.readPatterns(context(List.of("s-1")));

    assertThatThrownBy(() -> limits.current(LimitType.STAKE_DAILY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("STAKE_DAILY:counter")
        .hasMessageContaining("WRONGTYPE");
    assertThatThrownBy(() -> limits.override(LimitType.STAKE_DAILY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("STAKE_DAILY:override")
        .hasMessageContaining("WRONGTYPE");
    assertThat(patterns.recentBetCount()).isZero();
    assertThat(patterns.recentStakeAmounts()).isEmpty();
    assertThatThrownBy(() -> patterns.selectionBetCount("s-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("repeated-same-selection:s-1")
        .hasMessageContaining("WRONGTYPE");
  }

  @Test
  void patternSnapshotMatchesTheExistingHistoryContracts() {
    history.recordBet(USER, "b-1", 1_000L, List.of("s-1"), NOW.minusSeconds(50));
    history.recordBet(USER, "b-2", 2_000L, List.of("s-1"), NOW.minusSeconds(20));
    history.recordBet(USER, "b-3", 3_000L, List.of("s-2"), NOW.minusSeconds(10));

    PatternSnapshot snapshot = reader.readPatterns(context(List.of("s-1", "s-2")));

    assertThat(snapshot.recentBetCount()).isEqualTo(3L);
    assertThat(snapshot.recentStakeAmounts()).containsExactly(1_000L, 2_000L, 3_000L);
    assertThat(snapshot.selectionBetCount("s-1")).isEqualTo(2L);
    assertThat(snapshot.selectionBetCount("s-2")).isEqualTo(1L);
  }

  @Test
  void patternStakeSnapshotPreservesLongPrecisionAndSkipsEachCorruptMemberLikeDirectRead() {
    String bets = "history:user:" + USER + ":bets";
    template
        .opsForZSet()
        .add(bets, "precise|9007199254740993", NOW.minusSeconds(40).toEpochMilli());
    template
        .opsForZSet()
        .add(bets, "maximum|" + Long.MAX_VALUE, NOW.minusSeconds(30).toEpochMilli());
    template.opsForZSet().add(bets, "malformed|not-a-long", NOW.minusSeconds(20).toEpochMilli());
    template
        .opsForZSet()
        .add(bets, "overflow|9223372036854775808", NOW.minusSeconds(10).toEpochMilli());

    List<Long> direct = history.recentStakeAmounts(USER, 10);
    List<Long> captured = reader.readPatterns(context(List.of("s-1"))).recentStakeAmounts();

    assertThat(captured).containsExactlyElementsOf(direct);
    assertThat(captured).containsExactly(9_007_199_254_740_993L, Long.MAX_VALUE);
  }

  @Test
  void concurrentAtomicWriterIsObservedEntirelyBeforeOrAfterTheLimitSnapshot() throws Exception {
    writeState(10L, 100L);
    Set<List<Long>> allowed = Set.of(tuple(10L, 100L), tuple(20L, 200L));
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      pool.submit(
          () -> {
            await(start, failure);
            for (int i = 0; i < 250 && failure.get() == null; i++) {
              try {
                if ((i & 1) == 0) {
                  writeState(20L, 200L);
                } else {
                  writeState(10L, 100L);
                }
              } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
              }
            }
          });
      pool.submit(
          () -> {
            await(start, failure);
            for (int i = 0; i < 500 && failure.get() == null; i++) {
              try {
                LimitSnapshot snapshot = reader.readLimits(USER, Currency.KRW, NOW);
                List<Long> observed =
                    List.of(
                        snapshot.current(LimitType.STAKE_DAILY),
                        snapshot.current(LimitType.STAKE_WEEKLY),
                        snapshot.current(LimitType.STAKE_MONTHLY),
                        snapshot.current(LimitType.SELECTIONS_PER_MINUTE),
                        snapshot.override(LimitType.STAKE_DAILY).orElseThrow(),
                        snapshot.override(LimitType.STAKE_WEEKLY).orElseThrow(),
                        snapshot.override(LimitType.STAKE_MONTHLY).orElseThrow(),
                        snapshot.override(LimitType.SELECTIONS_PER_MINUTE).orElseThrow());
                if (!allowed.contains(observed)) {
                  failure.compareAndSet(null, new AssertionError("hybrid snapshot: " + observed));
                }
              } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
              }
            }
          });
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }
    assertThat(failure.get()).isNull();
  }

  @Test
  void patternWriterIsObservedEntirelyBeforeOrAfterTheSnapshot() throws Exception {
    PatternContext context = context(List.of("s-1", "s-2"));
    assertThat(observe(reader.readPatterns(context)))
        .isEqualTo(new PatternObservation(0L, List.of(), 0L, 0L));

    Set<PatternObservation> allowed =
        Set.of(
            new PatternObservation(0L, List.of(), 0L, 0L),
            new PatternObservation(1L, List.of(1_234L), 1L, 1L));
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      pool.submit(
          () -> {
            await(start, failure);
            try {
              writePatternState();
            } catch (Throwable throwable) {
              failure.compareAndSet(null, throwable);
            }
          });
      pool.submit(
          () -> {
            await(start, failure);
            for (int i = 0; i < 500 && failure.get() == null; i++) {
              try {
                PatternObservation observed = observe(reader.readPatterns(context));
                if (!allowed.contains(observed)) {
                  failure.compareAndSet(
                      null, new AssertionError("hybrid pattern snapshot: " + observed));
                }
              } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
              }
            }
          });
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(failure.get()).isNull();
    assertThat(observe(reader.readPatterns(context)))
        .isEqualTo(new PatternObservation(1L, List.of(1_234L), 1L, 1L));
  }

  private static void writeState(long current, long limit) {
    List<String> keys = new java.util.ArrayList<>();
    for (LimitType type : LimitType.values()) {
      keys.addAll(LimitKeys.userKeyPair(USER, type));
    }
    keys.add("limit:override:user:" + USER);
    RedisScript<Long> script =
        new DefaultRedisScript<>(
            """
            for i = 1, 4 do
              local keyIndex = (i - 1) * 2 + 1
              redis.call('DEL', KEYS[keyIndex], KEYS[keyIndex + 1])
              redis.call('ZADD', KEYS[keyIndex], ARGV[1], 'bet|' .. ARGV[2])
              redis.call('SET', KEYS[keyIndex + 1], ARGV[2])
              redis.call('HSET', KEYS[9], ARGV[i + 3], ARGV[3])
            end
            return 1
            """,
            Long.class);
    template.execute(
        script,
        keys,
        Long.toString(NOW.toEpochMilli()),
        Long.toString(current),
        Long.toString(limit),
        "STAKE_DAILY:KRW",
        "STAKE_WEEKLY:KRW",
        "STAKE_MONTHLY:KRW",
        "SELECTIONS_PER_MINUTE:KRW");
  }

  private static List<Long> tuple(long current, long limit) {
    return List.of(current, current, current, current, limit, limit, limit, limit);
  }

  private static void writePatternState() {
    RedisScript<Long> script =
        new DefaultRedisScript<>(
            """
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
            for i = 2, #KEYS do
              redis.call('ZADD', KEYS[i], ARGV[1], ARGV[3])
            end
            return 1
            """,
            Long.class);
    template.execute(
        script,
        List.of(
            "history:user:" + USER + ":bets",
            "history:user:" + USER + ":sel:s-1",
            "history:user:" + USER + ":sel:s-2"),
        Long.toString(NOW.toEpochMilli()),
        "atomic-bet|1234",
        "atomic-bet");
  }

  private static PatternObservation observe(PatternSnapshot snapshot) {
    return new PatternObservation(
        snapshot.recentBetCount(),
        snapshot.recentStakeAmounts(),
        snapshot.selectionBetCount("s-1"),
        snapshot.selectionBetCount("s-2"));
  }

  private static void await(CountDownLatch latch, AtomicReference<Throwable> failure) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failure.compareAndSet(null, e);
    }
  }

  private static long commandCalls(String command) throws Exception {
    String prefix = "cmdstat_" + command + ":";
    String stats = REDIS.execInContainer("redis-cli", "--raw", "INFO", "commandstats").getStdout();
    for (String line : stats.split("\\R")) {
      if (line.startsWith(prefix)) {
        for (String field : line.substring(prefix.length()).split(",")) {
          if (field.startsWith("calls=")) {
            return Long.parseLong(field.substring("calls=".length()));
          }
        }
      }
    }
    return 0L;
  }

  private static PatternContext context(List<String> selections) {
    return new PatternContext(USER, "candidate", Money.krw(10_000L), selections, NOW);
  }

  private static RiskPatternProperties enabledPatterns() {
    return new RiskPatternProperties(
        new RiskPatternProperties.RapidBetting(true, 60, 30, PatternAction.SUSPECT),
        new RiskPatternProperties.SuddenStakeIncrease(true, 10, 10, PatternAction.SUSPECT),
        new RiskPatternProperties.RepeatedSameSelection(true, 24, 5, PatternAction.REVIEW));
  }

  private record PatternObservation(
      long recentBets, List<Long> recentStakes, long firstSelection, long secondSelection) {}
}
