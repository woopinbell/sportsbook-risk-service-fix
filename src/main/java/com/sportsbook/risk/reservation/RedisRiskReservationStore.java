package com.sportsbook.risk.reservation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.counter.LimitKeys;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.policy.PatternAction;
import com.sportsbook.risk.policy.RiskLimitProperties;
import com.sportsbook.risk.service.LimitRejection;
import com.sportsbook.risk.service.RiskCheckCommand;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/** Standalone-Redis reservation store. Each state transition executes as one Lua operation. */
@Component
public class RedisRiskReservationStore {

  private static final String USER_OVERRIDE_PREFIX = "limit:override:user:";
  private static final long TTL_MULTIPLIER = 2L;
  private static final long MIN_TTL_SECONDS = 60L;

  private final StringRedisTemplate redis;
  private final RiskLimitProperties limits;
  private final RiskReservationProperties reservations;
  private final ObjectMapper mapper;
  private final RedisScript<String> reserveScript;
  private final RedisScript<String> commitScript;
  private final RedisScript<String> releaseScript;
  private final Timer reserveTimer;
  private final Timer commitTimer;
  private final Timer releaseTimer;
  private final Counter expirationCounter;

  public RedisRiskReservationStore(
      StringRedisTemplate redis,
      RiskLimitProperties limits,
      RiskReservationProperties reservations,
      ObjectMapper mapper,
      MeterRegistry meters) {
    this.redis = redis;
    this.limits = limits;
    this.reservations = reservations;
    this.mapper = mapper;
    this.reserveScript = script("scripts/risk-reserve.lua");
    this.commitScript = script("scripts/risk-commit.lua");
    this.releaseScript = script("scripts/risk-release.lua");
    this.reserveTimer = timer(meters, "reserve");
    this.commitTimer = timer(meters, "commit");
    this.releaseTimer = timer(meters, "release");
    this.expirationCounter =
        Counter.builder("risk_reservation_expirations_total")
            .description("Reservation leases transitioned after expiry")
            .register(meters);
    Gauge.builder("risk_reservations_active", this, RedisRiskReservationStore::activeCount)
        .description("Active risk reservations stored in Redis")
        .register(meters);
  }

  public ReservationDecision reserve(RiskCheckCommand command) {
    return reserve(command, null, List.of());
  }

  public ReservationDecision reserve(RiskCheckCommand command, LimitRejection patternBlock) {
    return reserve(command, patternBlock, List.of());
  }

  public ReservationDecision reserve(
      RiskCheckCommand command, LimitRejection patternBlock, List<PatternMatch> patternsFlagged) {
    List<PatternMatch> immutablePatterns = List.copyOf(patternsFlagged);
    return reserveTimer.record(() -> doReserve(command, patternBlock, immutablePatterns));
  }

  public ReservationTransition commit(String betId, Instant now) {
    return commitTimer.record(() -> doCommit(betId, now));
  }

  public ReservationTransition release(String betId, Instant now) {
    return releaseTimer.record(() -> doTerminate(betId, now, "RELEASED", null));
  }

  public boolean needsPatternEvaluation(RiskCheckCommand command) {
    List<Object> existing =
        redis
            .opsForHash()
            .multiGet(
                ReservationKeys.reservation(command.betId()),
                List.of("state", "fingerprint", "expiresAt"));
    if (existing == null || existing.isEmpty() || existing.get(0) == null) {
      return true;
    }
    if (!ReservationFingerprint.of(command).equals(existing.get(1))) {
      return false;
    }
    String state = existing.get(0).toString();
    if ("EXPIRED".equals(state)) {
      return true;
    }
    if (!"RESERVED".equals(state) || existing.get(2) == null) {
      return false;
    }
    try {
      return Long.parseLong(existing.get(2).toString()) <= command.now().toEpochMilli();
    } catch (NumberFormatException malformedExpiry) {
      return true;
    }
  }

  long activeCount() {
    try {
      String raw = redis.opsForValue().get(ReservationKeys.ACTIVE_COUNT);
      return raw == null ? 0L : Math.max(0L, Long.parseLong(raw));
    } catch (RuntimeException ignored) {
      return 0L;
    }
  }

  private ReservationDecision doReserve(
      RiskCheckCommand command, LimitRejection patternBlock, List<PatternMatch> patternsFlagged) {
    List<String> keys = new ArrayList<>();
    keys.add(ReservationKeys.reservation(command.betId()));
    keys.add(ReservationKeys.userReservations(command.userId()));
    keys.add(ReservationKeys.reservedStakeSum(command.userId()));
    keys.add(ReservationKeys.reservedSelectionSum(command.userId()));
    for (LimitType type : LimitType.values()) {
      keys.addAll(LimitKeys.userKeyPair(command.userId(), type));
    }
    keys.add(USER_OVERRIDE_PREFIX + command.userId());
    keys.add(ReservationKeys.ACTIVE_COUNT);

    Currency currency = command.stake().currency();
    List<String> args = new ArrayList<>();
    args.add(Long.toString(command.now().toEpochMilli()));
    args.add(Long.toString(reservations.lease().toMillis()));
    args.add(Long.toString(reservations.retention().toMillis()));
    args.add(ReservationFingerprint.of(command));
    args.add(command.userId());
    args.add(command.betId());
    args.add(Long.toString(command.stake().amount()));
    args.add(Integer.toString(command.selectionIds().size()));
    args.add(currency.name());
    args.add(Long.toString(limits.singleBetMax(currency)));
    args.add(Long.toString(limits.stakeDaily(currency)));
    args.add(Long.toString(limits.stakeWeekly(currency)));
    args.add(Long.toString(limits.stakeMonthly(currency)));
    args.add(Integer.toString(limits.selectionsPerMinute()));
    for (LimitType type : LimitType.values()) {
      args.add(Long.toString(type.window().toMillis()));
      args.add(Long.toString(ttlSecondsFor(type.window())));
    }
    Currency patternCurrency = patternBlock == null ? null : patternBlock.currency();
    args.add(patternBlock == null ? "0" : "1");
    args.add(patternBlock == null ? "" : patternBlock.reason());
    args.add(patternBlock == null ? "0" : Long.toString(patternBlock.current()));
    args.add(patternBlock == null ? "0" : Long.toString(patternBlock.limit()));
    args.add(patternBlock == null ? "0" : Long.toString(patternBlock.requested()));
    args.add(patternCurrency == null ? "" : patternCurrency.name());
    args.add(patternBlock == null ? "" : patternBlock.action());
    args.add(patternsJson(patternsFlagged));

    String raw = redis.execute(reserveScript, keys, args.toArray());
    JsonNode wire = json(raw, "reserve");
    expirationCounter.increment(wire.path("expired").asLong());
    List<PatternMatch> persistedPatterns = patterns(wire);
    return switch (wire.path("status").asText()) {
      case "APPROVED" -> approved(wire, persistedPatterns);
      case "REJECTED" ->
          ReservationDecision.rejected(
              rejection(wire), wire.path("replayed").asBoolean(false), persistedPatterns);
      case "CONFLICT" -> ReservationDecision.conflict();
      default ->
          throw new IllegalStateException(
              "Redis reservation script returned unknown status: " + wire);
    };
  }

  private ReservationTransition doCommit(String betId, Instant now) {
    List<String> args = new ArrayList<>();
    args.add(Long.toString(now.toEpochMilli()));
    args.add(Long.toString(reservations.retention().toMillis()));
    for (LimitType type : LimitType.values()) {
      args.add(Long.toString(type.window().toMillis()));
      args.add(Long.toString(ttlSecondsFor(type.window())));
    }
    String raw =
        redis.execute(
            commitScript,
            List.of(ReservationKeys.reservation(betId), ReservationKeys.ACTIVE_COUNT),
            args.toArray());
    ReservationTransition result = transition(raw, "commit");
    if (result == ReservationTransition.EXPIRED) {
      expirationCounter.increment();
    }
    return result;
  }

  private ReservationTransition doTerminate(
      String betId, Instant now, String targetState, LimitRejection rejection) {
    Currency rejectionCurrency = rejection == null ? null : rejection.currency();
    String raw =
        redis.execute(
            releaseScript,
            List.of(ReservationKeys.reservation(betId), ReservationKeys.ACTIVE_COUNT),
            Long.toString(now.toEpochMilli()),
            Long.toString(reservations.retention().toMillis()),
            targetState,
            rejection == null ? "" : rejection.reason(),
            rejection == null ? "0" : Long.toString(rejection.current()),
            rejection == null ? "0" : Long.toString(rejection.limit()),
            rejection == null ? "0" : Long.toString(rejection.requested()),
            rejectionCurrency == null ? "" : rejectionCurrency.name(),
            rejection == null ? "" : rejection.action());
    ReservationTransition result = transition(raw, targetState.toLowerCase());
    if (result == ReservationTransition.EXPIRED) {
      expirationCounter.increment();
    }
    return result;
  }

  private ReservationDecision approved(JsonNode wire, List<PatternMatch> patternsFlagged) {
    ReservationState state = ReservationState.valueOf(wire.path("state").asText());
    Instant expiresAt =
        state == ReservationState.RESERVED
            ? Instant.ofEpochMilli(wire.path("expiresAt").asLong())
            : null;
    return ReservationDecision.approved(
        state, expiresAt, wire.path("replayed").asBoolean(false), patternsFlagged);
  }

  private String patternsJson(List<PatternMatch> patternsFlagged) {
    try {
      return mapper.writeValueAsString(patternsFlagged);
    } catch (Exception failure) {
      throw new IllegalStateException("Failed to encode reservation pattern decisions", failure);
    }
  }

  private List<PatternMatch> patterns(JsonNode wire) {
    String raw = wire.path("patternsJson").asText("[]");
    try {
      return mapper.readValue(raw, new TypeReference<List<PatternMatch>>() {});
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Redis reservation script returned malformed pattern decisions", failure);
    }
  }

  private static LimitRejection rejection(JsonNode wire) {
    JsonNode currencyNode = wire.get("currency");
    Currency currency =
        currencyNode == null || currencyNode.isNull()
            ? null
            : Currency.valueOf(currencyNode.asText());
    return new LimitRejection(
        wire.path("reason").asText(),
        currency,
        wire.path("current").asLong(),
        wire.path("limit").asLong(),
        wire.path("requested").asLong(),
        wire.path("action").asText(PatternAction.BLOCK.name()));
  }

  private JsonNode json(String raw, String operation) {
    if (raw == null) {
      throw new IllegalStateException("Redis " + operation + " script returned no payload");
    }
    try {
      return mapper.readTree(raw);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Redis " + operation + " script returned malformed JSON", failure);
    }
  }

  private static ReservationTransition transition(String raw, String operation) {
    if (raw == null) {
      throw new IllegalStateException("Redis " + operation + " script returned no result");
    }
    try {
      return ReservationTransition.valueOf(raw);
    } catch (IllegalArgumentException failure) {
      throw new IllegalStateException(
          "Redis " + operation + " script returned unknown result: " + raw, failure);
    }
  }

  private static Timer timer(MeterRegistry meters, String operation) {
    return Timer.builder("risk_reservation_lua_latency_seconds")
        .description("Latency of an atomic Redis reservation transition")
        .tag("operation", operation)
        .register(meters);
  }

  private static long ttlSecondsFor(Duration window) {
    return Math.max(MIN_TTL_SECONDS, window.getSeconds() * TTL_MULTIPLIER);
  }

  private static RedisScript<String> script(String path) {
    try {
      String source =
          StreamUtils.copyToString(
              new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
      return new DefaultRedisScript<>(source, String.class);
    } catch (Exception failure) {
      throw new IllegalStateException("Failed to load " + path + " from classpath", failure);
    }
  }
}
