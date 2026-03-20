package com.sportsbook.risk.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.policy.PatternAction;
import com.sportsbook.risk.service.LimitRejection;
import com.sportsbook.risk.service.RiskCheckCommand;
import com.sportsbook.risk.service.RiskCheckOutcome;
import com.sportsbook.risk.service.RiskCheckService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskCheckControllerTest {

  private static final Instant FIXED = Instant.parse("2026-05-28T10:00:00Z");

  private final ObjectMapper json = new ObjectMapper();
  @Mock private RiskCheckService service;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    RiskCheckController controller = new RiskCheckController(service, clock);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new RestExceptionHandler())
            .build();
  }

  @Test
  void approvedRequestReturnsApprovedJson() throws Exception {
    RiskCheckRequest req = new RiskCheckRequest("u-1", "b-1", Money.krw(10_000L), List.of("s-1"));
    when(service.check(any(RiskCheckCommand.class)))
        .thenReturn(RiskCheckOutcome.approved(List.of()));

    mvc.perform(
            post("/internal/v1/risk/check")
                .contentType("application/json")
                .content(json.writeValueAsBytes(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approved").value(true));
  }

  @Test
  void limitRejectionReturnsApprovedFalseWithLimitInfo() throws Exception {
    RiskCheckRequest req = new RiskCheckRequest("u-1", "b-1", Money.krw(100_000L), List.of("s-1"));
    LimitRejection rejection =
        new LimitRejection(
            "STAKE_DAILY_LIMIT_EXCEEDED", Currency.KRW, 950_000L, 1_000_000L, 100_000L, "BLOCK");
    when(service.check(any(RiskCheckCommand.class)))
        .thenReturn(RiskCheckOutcome.rejectedByLimit(rejection, List.of()));

    mvc.perform(
            post("/internal/v1/risk/check")
                .contentType("application/json")
                .content(json.writeValueAsBytes(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approved").value(false))
        .andExpect(jsonPath("$.rejectionReason").value("STAKE_DAILY_LIMIT_EXCEEDED"))
        .andExpect(jsonPath("$.limitInfo.current").value(950_000))
        .andExpect(jsonPath("$.limitInfo.limit").value(1_000_000))
        .andExpect(jsonPath("$.limitInfo.requested").value(100_000));
  }

  @Test
  void patternMatchSurfacedInResponse() throws Exception {
    RiskCheckRequest req = new RiskCheckRequest("u-1", "b-1", Money.krw(10_000L), List.of("s-1"));
    PatternMatch flagged = new PatternMatch("rapid-betting", PatternAction.SUSPECT, "30 reached");
    when(service.check(any(RiskCheckCommand.class)))
        .thenReturn(RiskCheckOutcome.approved(List.of(flagged)));

    mvc.perform(
            post("/internal/v1/risk/check")
                .contentType("application/json")
                .content(json.writeValueAsBytes(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.patternsFlagged[0].ruleName").value("rapid-betting"))
        .andExpect(jsonPath("$.patternsFlagged[0].action").value("SUSPECT"));
  }

  @Test
  void invalidPayloadReturnsRfc7807Problem() throws Exception {
    String invalid =
        "{\"userId\":\"\",\"betId\":\"b-1\",\"stake\":{\"amount\":10000,\"currency\":\"KRW\"},"
            + "\"selectionIds\":[\"s-1\"]}";

    mvc.perform(post("/internal/v1/risk/check").contentType("application/json").content(invalid))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
  }
}
