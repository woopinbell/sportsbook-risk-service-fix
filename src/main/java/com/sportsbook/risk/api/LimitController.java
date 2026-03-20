package com.sportsbook.risk.api;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.limit.LimitOverrideStore;
import com.sportsbook.risk.limit.LimitResolver;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-facing per-user limit management. The admin-api is the production caller; this
 * controller intentionally exposes the same {@code /internal/v1/...} prefix as the check API so the
 * betting platform's internal traffic stays on one path namespace.
 *
 * <p>SELECTIONS_PER_MINUTE is currency-agnostic — the controller stores any SELECTIONS_PER_MINUTE
 * override under {@link Currency#KRW} as a sentinel so callers do not have to know that convention.
 * Lookups apply the same normalisation.
 */
@RestController
@RequestMapping("/internal/v1/risk/limits")
public class LimitController {

  private static final Currency COUNT_SENTINEL_CURRENCY = Currency.KRW;

  private final LimitOverrideStore store;
  private final LimitResolver resolver;

  public LimitController(LimitOverrideStore store, LimitResolver resolver) {
    this.store = store;
    this.resolver = resolver;
  }

  @GetMapping("/{userId}")
  public UserLimitsResponse get(@PathVariable String userId) {
    List<UserLimitsResponse.Entry> entries = new ArrayList<>();
    for (LimitType type : LimitType.values()) {
      if (type == LimitType.SELECTIONS_PER_MINUTE) {
        entries.add(entryFor(userId, type, COUNT_SENTINEL_CURRENCY, true));
      } else {
        for (Currency currency : Currency.values()) {
          entries.add(entryFor(userId, type, currency, false));
        }
      }
    }
    return new UserLimitsResponse(userId, entries);
  }

  @PatchMapping("/{userId}")
  public ResponseEntity<Void> update(
      @PathVariable String userId, @Valid @RequestBody LimitUpdateRequest request) {
    Currency currency = effectiveCurrency(request.limitType(), request.currency());
    store.setUserOverride(userId, request.limitType(), currency, request.amount());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{userId}/{limitType}/{currency}")
  public ResponseEntity<Void> clear(
      @PathVariable String userId,
      @PathVariable LimitType limitType,
      @PathVariable Currency currency) {
    Currency effective = effectiveCurrency(limitType, currency);
    store.clearUserOverride(userId, limitType, effective);
    return ResponseEntity.noContent().build();
  }

  private UserLimitsResponse.Entry entryFor(
      String userId, LimitType type, Currency currency, boolean countSentinel) {
    Optional<Long> override = store.findUserOverride(userId, type, currency);
    long value = override.orElseGet(() -> resolver.resolveUser(userId, type, currency));
    String source =
        override.isPresent()
            ? UserLimitsResponse.Entry.SOURCE_OVERRIDE
            : UserLimitsResponse.Entry.SOURCE_POLICY;
    // For count-style limits the currency is meaningless; null it out on the wire.
    return new UserLimitsResponse.Entry(
        type.name(), countSentinel ? null : currency, value, source);
  }

  private static Currency effectiveCurrency(LimitType type, Currency requested) {
    if (type == LimitType.SELECTIONS_PER_MINUTE) {
      return COUNT_SENTINEL_CURRENCY;
    }
    if (requested == null) {
      throw new IllegalArgumentException("currency is required for " + type);
    }
    return requested;
  }
}
