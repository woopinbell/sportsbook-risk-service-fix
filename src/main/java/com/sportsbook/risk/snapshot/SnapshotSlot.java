package com.sportsbook.risk.snapshot;

import java.util.Objects;

/** A tagged Lua result that defers a Redis slot failure until Java reaches that decision step. */
record SnapshotSlot<T>(T value, String error) {

  static <T> SnapshotSlot<T> success(T value) {
    return new SnapshotSlot<>(value, null);
  }

  static <T> SnapshotSlot<T> failure(String error) {
    return new SnapshotSlot<>(null, Objects.requireNonNull(error, "error"));
  }

  T valueOrThrow(String slot) {
    if (error != null) {
      throw new IllegalStateException("Redis snapshot slot " + slot + " failed: " + error);
    }
    return value;
  }
}
