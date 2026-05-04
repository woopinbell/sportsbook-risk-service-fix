package com.sportsbook.risk.snapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pattern history facts captured by one Redis Lua invocation. */
public record PatternSnapshot(
    SnapshotSlot<Long> recentBets,
    SnapshotSlot<List<Long>> recentStakes,
    Map<String, SnapshotSlot<Long>> selectionBets) {

  public PatternSnapshot {
    Objects.requireNonNull(recentBets, "recentBets");
    Objects.requireNonNull(recentStakes, "recentStakes");
    selectionBets = Map.copyOf(Objects.requireNonNull(selectionBets, "selectionBets"));
  }

  public static PatternSnapshot successful(
      long recentBets, List<Long> recentStakes, Map<String, Long> selectionBets) {
    Map<String, SnapshotSlot<Long>> selections = new LinkedHashMap<>();
    selectionBets.forEach((key, value) -> selections.put(key, SnapshotSlot.success(value)));
    return new PatternSnapshot(
        SnapshotSlot.success(recentBets),
        SnapshotSlot.success(List.copyOf(recentStakes)),
        selections);
  }

  public long recentBetCount() {
    return recentBets.valueOrThrow("rapid-betting");
  }

  public List<Long> recentStakeAmounts() {
    return recentStakes.valueOrThrow("sudden-stake-increase");
  }

  public long selectionBetCount(String selectionId) {
    SnapshotSlot<Long> slot = selectionBets.get(Objects.requireNonNull(selectionId, "selectionId"));
    if (slot == null) {
      throw new IllegalStateException("Missing pattern snapshot slot for selection " + selectionId);
    }
    return slot.valueOrThrow("repeated-same-selection:" + selectionId);
  }
}
