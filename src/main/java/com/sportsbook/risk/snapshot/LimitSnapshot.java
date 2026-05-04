package com.sportsbook.risk.snapshot;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.counter.LimitType;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Counter sums and raw per-user overrides captured by one Redis Lua invocation. */
public record LimitSnapshot(
    String userId,
    Currency currency,
    Map<LimitType, SnapshotSlot<Long>> counters,
    Map<LimitType, SnapshotSlot<String>> overrides) {

  public LimitSnapshot {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(currency, "currency");
    counters = Map.copyOf(Objects.requireNonNull(counters, "counters"));
    overrides = Map.copyOf(Objects.requireNonNull(overrides, "overrides"));
  }

  public long current(LimitType type) {
    return required(counters, type, "counter").valueOrThrow(type.name() + ":counter");
  }

  public Optional<Long> override(LimitType type) {
    String raw = required(overrides, type, "override").valueOrThrow(type.name() + ":override");
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(raw));
    } catch (NumberFormatException e) {
      String key = "limit:override:user:" + userId;
      String field = type.name() + ":" + currency.name();
      throw new IllegalStateException(
          "Limit override at " + key + "[" + field + "] is not a long: '" + raw + "'", e);
    }
  }

  private static <T> SnapshotSlot<T> required(
      Map<LimitType, SnapshotSlot<T>> slots, LimitType type, String kind) {
    SnapshotSlot<T> slot = slots.get(Objects.requireNonNull(type, "type"));
    if (slot == null) {
      throw new IllegalStateException("Missing " + kind + " snapshot slot for " + type);
    }
    return slot;
  }
}
