package com.sportsbook.risk.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sportsbook.protocol.value.Money;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Input shared by diagnostic checks and atomic risk reservation creation. */
public record RiskCheckRequest(
    @NotBlank String userId,
    @NotBlank String betId,
    @NotNull Money stake,
    @NotEmpty List<@NotBlank String> selectionIds) {

  @JsonIgnore
  @AssertTrue(message = "stake amount must be positive")
  public boolean isStakeAmountPositive() {
    return stake == null || stake.amount() > 0L;
  }
}
