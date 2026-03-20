package com.sportsbook.risk.api;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.counter.LimitType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * REST DTO for {@code PATCH /internal/v1/risk/limits/{userId}}. {@code currency} is nullable
 * because {@link LimitType#SELECTIONS_PER_MINUTE} is currency-agnostic; the controller normalises
 * to {@link Currency#KRW} on storage so the operator does not need to know the convention.
 */
public record LimitUpdateRequest(
    @NotNull LimitType limitType, Currency currency, @PositiveOrZero long amount) {}
