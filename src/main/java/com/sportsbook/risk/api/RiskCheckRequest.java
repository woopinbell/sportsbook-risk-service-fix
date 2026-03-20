package com.sportsbook.risk.api;

import com.sportsbook.protocol.value.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** REST DTO for {@code POST /internal/v1/risk/check} — the betting-service critical-path input. */
public record RiskCheckRequest(
    @NotBlank String userId,
    @NotBlank String betId,
    @NotNull Money stake,
    @NotEmpty List<@NotBlank String> selectionIds) {}
