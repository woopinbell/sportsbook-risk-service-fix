package com.sportsbook.risk.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.counter.LimitKeys;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.pattern.PatternContext;
import com.sportsbook.risk.policy.RiskPatternProperties;
import com.sportsbook.risk.reservation.ReservationKeys;
import com.sportsbook.risk.reservation.RiskReservationProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/** Standalone-Redis implementation that obtains all risk facts through one Lua script. */
@Component
public class RedisRiskSnapshotReader implements RiskSnapshotReader {

  private static final long TTL_MULTIPLIER = 2L;
  private static final long MIN_TTL_SECONDS = 60L;
  private static final String USER_OVERRIDE_PREFIX = "limit:override:user:";
  private static final String HISTORY_PREFIX = "history:user:";
  private static final String HISTORY_BETS_SUFFIX = ":bets";
  private static final String HISTORY_SELECTION_INFIX = ":sel:";

  private final StringRedisTemplate redis;
  private final RiskPatternProperties patterns;
  private final RiskReservationProperties reservations;
  private final ObjectMapper mapper;
  private final RedisScript<String> snapshotScript;
  private final RedisScript<String> limitScript;
  private final RedisScript<String> patternScript;
  private final Counter expirationCounter;

  public RedisRiskSnapshotReader(
      StringRedisTemplate redis,
      RiskPatternProperties patterns,
      RiskReservationProperties reservations,
      ObjectMapper mapper,
      MeterRegistry meters) {
    this.redis = redis;
    this.patterns = patterns;
    this.reservations = reservations;
    this.mapper = mapper;
    this.snapshotScript = new DefaultRedisScript<>(load("scripts/risk-snapshot.lua"), String.class);
    this.limitScript =
        new DefaultRedisScript<>(load("scripts/risk-limit-snapshot.lua"), String.class);
    this.patternScript =
        new DefaultRedisScript<>(load("scripts/risk-pattern-snapshot.lua"), String.class);
    this.expirationCounter =
        Counter.builder("risk_reservation_expirations_total")
            .description("Reservation leases transitioned after expiry")
            .register(meters);
  }

  @Override
  public RiskSnapshot read(String userId, Currency currency, PatternContext context) {
    List<String> keys = new ArrayList<>();
    List<String> args = new ArrayList<>();
    args.add(Long.toString(context.now().toEpochMilli()));
    for (LimitType type : LimitType.values()) {
      keys.addAll(LimitKeys.userKeyPair(userId, type));
      args.add(Long.toString(type.window().toMillis()));
      args.add(Long.toString(ttlSecondsFor(type.window())));
    }
    keys.add(USER_OVERRIDE_PREFIX + userId);
    for (LimitType type : LimitType.values()) {
      args.add(overrideField(type, currency));
    }
    keys.add(ReservationKeys.userReservations(userId));
    keys.add(ReservationKeys.reservedStakeSum(userId));
    keys.add(ReservationKeys.reservedSelectionSum(userId));
    keys.add(ReservationKeys.ACTIVE_COUNT);

    RiskPatternProperties.RapidBetting rapid = patterns.rapidBetting();
    RiskPatternProperties.SuddenStakeIncrease sudden = patterns.suddenStakeIncrease();
    RiskPatternProperties.RepeatedSameSelection repeated = patterns.repeatedSameSelection();
    keys.add(HISTORY_PREFIX + context.userId() + HISTORY_BETS_SUFFIX);
    for (String selectionId : context.selectionIds()) {
      keys.add(HISTORY_PREFIX + context.userId() + HISTORY_SELECTION_INFIX + selectionId);
    }
    args.add(enabled(rapid.enabled()));
    args.add(Long.toString(Duration.ofSeconds(rapid.windowSeconds()).toMillis()));
    args.add(enabled(sudden.enabled()));
    args.add(Integer.toString(sudden.lookbackBets()));
    args.add(enabled(repeated.enabled()));
    args.add(Long.toString(Duration.ofHours(repeated.windowHours()).toMillis()));
    args.add(Long.toString(reservations.retention().toMillis()));

    RiskWire wire = readWire(snapshotScript, keys, args, RiskWire.class);
    expirationCounter.increment(wire.expired());
    return new RiskSnapshot(
        toLimitSnapshot(userId, currency, wire.limits()),
        toPatternSnapshot(context, wire.patterns()));
  }

  @Override
  public LimitSnapshot readLimits(String userId, Currency currency, Instant now) {
    List<String> keys = new ArrayList<>();
    List<String> args = new ArrayList<>();
    args.add(Long.toString(now.toEpochMilli()));
    for (LimitType type : LimitType.values()) {
      keys.addAll(LimitKeys.userKeyPair(userId, type));
      args.add(Long.toString(type.window().toMillis()));
      args.add(Long.toString(ttlSecondsFor(type.window())));
    }
    keys.add(USER_OVERRIDE_PREFIX + userId);
    for (LimitType type : LimitType.values()) {
      args.add(overrideField(type, currency));
    }
    args.add(Long.toString(reservations.retention().toMillis()));
    keys.add(ReservationKeys.userReservations(userId));
    keys.add(ReservationKeys.reservedStakeSum(userId));
    keys.add(ReservationKeys.reservedSelectionSum(userId));
    keys.add(ReservationKeys.ACTIVE_COUNT);

    LimitWire wire = readWire(limitScript, keys, args, LimitWire.class);
    expirationCounter.increment(wire.expired());
    return toLimitSnapshot(userId, currency, wire);
  }

  private LimitSnapshot toLimitSnapshot(String userId, Currency currency, LimitWire wire) {
    Map<LimitType, SnapshotSlot<Long>> counters = new EnumMap<>(LimitType.class);
    Map<LimitType, SnapshotSlot<String>> overrides = new EnumMap<>(LimitType.class);
    for (LimitType type : LimitType.values()) {
      counters.put(type, longSlot(required(wire.counters(), type.name())));
      overrides.put(type, stringSlot(required(wire.overrides(), type.name())));
    }
    return new LimitSnapshot(userId, currency, counters, overrides);
  }

  @Override
  public PatternSnapshot readPatterns(PatternContext context) {
    RiskPatternProperties.RapidBetting rapid = patterns.rapidBetting();
    RiskPatternProperties.SuddenStakeIncrease sudden = patterns.suddenStakeIncrease();
    RiskPatternProperties.RepeatedSameSelection repeated = patterns.repeatedSameSelection();

    List<String> keys = new ArrayList<>();
    keys.add(HISTORY_PREFIX + context.userId() + HISTORY_BETS_SUFFIX);
    for (String selectionId : context.selectionIds()) {
      keys.add(HISTORY_PREFIX + context.userId() + HISTORY_SELECTION_INFIX + selectionId);
    }
    List<String> args =
        List.of(
            Long.toString(context.now().toEpochMilli()),
            enabled(rapid.enabled()),
            Long.toString(Duration.ofSeconds(rapid.windowSeconds()).toMillis()),
            enabled(sudden.enabled()),
            Integer.toString(sudden.lookbackBets()),
            enabled(repeated.enabled()),
            Long.toString(Duration.ofHours(repeated.windowHours()).toMillis()));

    PatternWire wire = readWire(patternScript, keys, args, PatternWire.class);
    return toPatternSnapshot(context, wire);
  }

  private PatternSnapshot toPatternSnapshot(PatternContext context, PatternWire wire) {
    Map<String, SnapshotSlot<Long>> selections = new LinkedHashMap<>();
    for (int index = 0; index < context.selectionIds().size(); index++) {
      String selectionId = context.selectionIds().get(index);
      selections.put(
          selectionId, longSlot(required(wire.selections(), Integer.toString(index + 1))));
    }
    return new PatternSnapshot(longSlot(wire.rapid()), stakeSlot(wire.stakes()), selections);
  }

  private <T> T readWire(
      RedisScript<String> script, List<String> keys, List<String> args, Class<T> type) {
    String raw = redis.execute(script, keys, args.toArray());
    if (raw == null) {
      throw new IllegalStateException("Redis snapshot script returned no payload");
    }
    try {
      return mapper.readValue(raw, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Redis snapshot script returned malformed JSON", e);
    }
  }

  private static SnapshotSlot<Long> longSlot(WireSlot wire) {
    if (!wire.ok()) {
      return SnapshotSlot.failure(wire.error());
    }
    if (wire.value() == null || wire.value().isBlank()) {
      return SnapshotSlot.success(0L);
    }
    try {
      return SnapshotSlot.success(Long.parseLong(wire.value()));
    } catch (NumberFormatException e) {
      throw new IllegalStateException(
          "Redis snapshot value is not a long: '" + wire.value() + "'", e);
    }
  }

  private static SnapshotSlot<String> stringSlot(WireSlot wire) {
    return wire.ok() ? SnapshotSlot.success(wire.value()) : SnapshotSlot.failure(wire.error());
  }

  private SnapshotSlot<List<Long>> stakeSlot(WireSlot wire) {
    if (!wire.ok()) {
      return SnapshotSlot.failure(wire.error());
    }
    if (wire.value() == null || wire.value().isBlank()) {
      return SnapshotSlot.success(List.of());
    }
    List<String> values;
    try {
      values = mapper.readValue(wire.value(), new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Redis stake snapshot returned malformed JSON", e);
    }
    List<Long> stakes = new ArrayList<>();
    for (String value : values) {
      try {
        stakes.add(Long.parseLong(value));
      } catch (NumberFormatException ignored) {
        // Match RedisUserBetHistory: one corrupt member does not poison the remaining facts.
      }
    }
    return SnapshotSlot.success(List.copyOf(stakes));
  }

  private static WireSlot required(Map<String, WireSlot> slots, String name) {
    WireSlot slot = slots.get(name);
    if (slot == null) {
      throw new IllegalStateException("Redis snapshot response is missing slot " + name);
    }
    return slot;
  }

  private static long ttlSecondsFor(Duration window) {
    return Math.max(MIN_TTL_SECONDS, window.getSeconds() * TTL_MULTIPLIER);
  }

  private static String overrideField(LimitType type, Currency currency) {
    return type.name() + ":" + currency.name();
  }

  private static String enabled(boolean value) {
    return value ? "1" : "0";
  }

  private static String load(String path) {
    try {
      return StreamUtils.copyToString(
          new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load " + path + " from classpath", e);
    }
  }

  private record WireSlot(boolean ok, String value, String error) {}

  private record LimitWire(
      Map<String, WireSlot> counters, Map<String, WireSlot> overrides, long expired) {}

  private record PatternWire(WireSlot rapid, WireSlot stakes, Map<String, WireSlot> selections) {}

  private record RiskWire(LimitWire limits, PatternWire patterns, long expired) {}
}
