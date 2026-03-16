package com.sportsbook.risk.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.counter.LimitType;
import java.util.Optional;
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
class RedisLimitOverrideStoreTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory factory;
  private static StringRedisTemplate template;
  private static RedisLimitOverrideStore store;

  @BeforeAll
  static void startInfrastructure() {
    factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    factory.afterPropertiesSet();
    template = new StringRedisTemplate(factory);
    store = new RedisLimitOverrideStore(template);
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
  void userOverrideRoundTrips() {
    store.setUserOverride("u-1", LimitType.STAKE_DAILY, Currency.KRW, 5_000_000L);

    Optional<Long> found = store.findUserOverride("u-1", LimitType.STAKE_DAILY, Currency.KRW);

    assertThat(found).contains(5_000_000L);
  }

  @Test
  void missingUserOverrideReturnsEmpty() {
    Optional<Long> found = store.findUserOverride("u-2", LimitType.STAKE_DAILY, Currency.KRW);

    assertThat(found).isEmpty();
  }

  @Test
  void clearUserOverrideRemoves() {
    store.setUserOverride("u-1", LimitType.STAKE_DAILY, Currency.KRW, 5_000_000L);
    store.clearUserOverride("u-1", LimitType.STAKE_DAILY, Currency.KRW);

    Optional<Long> found = store.findUserOverride("u-1", LimitType.STAKE_DAILY, Currency.KRW);

    assertThat(found).isEmpty();
  }

  @Test
  void differentCurrenciesAreIsolated() {
    store.setUserOverride("u-1", LimitType.STAKE_DAILY, Currency.KRW, 5_000_000L);
    store.setUserOverride("u-1", LimitType.STAKE_DAILY, Currency.USD, 4_999L);

    assertThat(store.findUserOverride("u-1", LimitType.STAKE_DAILY, Currency.KRW))
        .contains(5_000_000L);
    assertThat(store.findUserOverride("u-1", LimitType.STAKE_DAILY, Currency.USD)).contains(4_999L);
  }

  @Test
  void marketOverrideUsesSeparateNamespace() {
    store.setMarketOverride("m-1", LimitType.STAKE_DAILY, Currency.KRW, 2_000_000L);

    assertThat(store.findMarketOverride("m-1", LimitType.STAKE_DAILY, Currency.KRW))
        .contains(2_000_000L);
    // Same id as a user should not collide.
    assertThat(store.findUserOverride("m-1", LimitType.STAKE_DAILY, Currency.KRW)).isEmpty();
  }

  @Test
  void rejectsNegativeAmount() {
    assertThatThrownBy(() -> store.setUserOverride("u-1", LimitType.STAKE_DAILY, Currency.KRW, -1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void corruptedValueRaisesIllegalState() {
    // Simulate operator error or admin tooling bug writing a non-numeric string.
    template.opsForHash().put("limit:override:user:u-1", "STAKE_DAILY:KRW", "abc");

    assertThatThrownBy(() -> store.findUserOverride("u-1", LimitType.STAKE_DAILY, Currency.KRW))
        .isInstanceOf(IllegalStateException.class);
  }
}
