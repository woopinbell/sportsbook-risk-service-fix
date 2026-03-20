package com.sportsbook.risk.pattern;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
class RedisUserBetHistoryTest {

  private static final Instant T0 = Instant.parse("2026-05-28T10:00:00Z");
  private static final String USER = "u-1";

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory factory;
  private static StringRedisTemplate template;
  private static RedisUserBetHistory history;

  @BeforeAll
  static void startInfrastructure() {
    factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    factory.afterPropertiesSet();
    template = new StringRedisTemplate(factory);
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
  void countBetsBetweenReturnsZeroForNewUser() {
    assertThat(history.countBetsBetween(USER, T0.minusSeconds(60), T0)).isZero();
  }

  @Test
  void recordedBetsCountedWithinRange() {
    history.recordBet(USER, "b-1", 10_000L, List.of("s-1"), T0);
    history.recordBet(USER, "b-2", 5_000L, List.of("s-1"), T0.plusSeconds(30));
    history.recordBet(USER, "b-3", 7_000L, List.of("s-1"), T0.plusSeconds(120));

    long inFirstMinute = history.countBetsBetween(USER, T0, T0.plusSeconds(60));
    long inTwoMinutes = history.countBetsBetween(USER, T0, T0.plusSeconds(120));

    assertThat(inFirstMinute).isEqualTo(2);
    assertThat(inTwoMinutes).isEqualTo(3);
  }

  @Test
  void recentStakeAmountsReturnsOldestFirst() {
    history.recordBet(USER, "b-1", 1_000L, List.of("s-1"), T0);
    history.recordBet(USER, "b-2", 2_000L, List.of("s-1"), T0.plusSeconds(10));
    history.recordBet(USER, "b-3", 3_000L, List.of("s-1"), T0.plusSeconds(20));

    // lookback=2 — newest two bets, oldest first.
    List<Long> amounts = history.recentStakeAmounts(USER, 2);

    assertThat(amounts).containsExactly(2_000L, 3_000L);
  }

  @Test
  void recentStakeAmountsCapsAtAvailableHistory() {
    history.recordBet(USER, "b-1", 1_000L, List.of("s-1"), T0);

    List<Long> amounts = history.recentStakeAmounts(USER, 10);

    assertThat(amounts).containsExactly(1_000L);
  }

  @Test
  void recentStakeAmountsRejectsNonPositiveLookback() {
    assertThat(history.recentStakeAmounts(USER, 0)).isEmpty();
    assertThat(history.recentStakeAmounts(USER, -3)).isEmpty();
  }

  @Test
  void countSelectionBetsIsolatesPerSelectionAndWindow() {
    history.recordBet(USER, "b-1", 10_000L, List.of("s-1"), T0);
    history.recordBet(USER, "b-2", 10_000L, List.of("s-1"), T0.plusSeconds(10));
    history.recordBet(USER, "b-3", 10_000L, List.of("s-2"), T0.plusSeconds(20));
    // Bet outside the lookback window.
    history.recordBet(USER, "b-old", 10_000L, List.of("s-1"), T0.minus(Duration.ofHours(48)));

    long s1Recent =
        history.countSelectionBets(USER, "s-1", Duration.ofHours(24), T0.plusSeconds(30));
    long s2Recent =
        history.countSelectionBets(USER, "s-2", Duration.ofHours(24), T0.plusSeconds(30));
    long s1OverWindow =
        history.countSelectionBets(USER, "s-1", Duration.ofHours(72), T0.plusSeconds(30));

    assertThat(s1Recent).isEqualTo(2);
    assertThat(s2Recent).isEqualTo(1);
    assertThat(s1OverWindow).isEqualTo(3);
  }

  @Test
  void recordBetRejectsNegativeStakeButTolerantOfMissingSelections() {
    history.recordBet(USER, "b-1", 10_000L, List.of(), T0); // empty selections allowed

    assertThat(history.countBetsBetween(USER, T0, T0.plusSeconds(1))).isEqualTo(1);
    assertThat(history.countSelectionBets(USER, "s-1", Duration.ofHours(1), T0.plusSeconds(1)))
        .isZero();
  }
}
