package com.sportsbook.risk.service;

import com.sportsbook.protocol.value.Currency;

/**
 * Breakdown of the single limit that tripped a check, included on every rejection response so the
 * caller can render a user-friendly message without re-querying. {@link #reason} is a stable enum-
 * like string (e.g. {@code STAKE_DAILY_LIMIT_EXCEEDED}) suitable for both the JSON response and the
 * {@code risk.limit.violated} Avro event.
 *
 * @param reason machine-readable reason code
 * @param currency present only for currency-scoped limits; {@code null} for count-based limits
 * @param current consumed amount or count before this candidate was applied
 * @param limit configured threshold
 * @param requested amount or selection count the candidate bet would add
 */
public record LimitRejection(
    String reason, Currency currency, long current, long limit, long requested, String action) {}
