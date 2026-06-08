package com.sportsbook.risk.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.risk.reservation.CommittedReservationReleaseException;
import com.sportsbook.risk.reservation.ReservationConflictException;
import com.sportsbook.risk.reservation.ReservationNotFoundException;
import com.sportsbook.risk.reservation.ReservationState;
import com.sportsbook.risk.reservation.RiskReservationOutcome;
import com.sportsbook.risk.reservation.RiskReservationService;
import com.sportsbook.risk.service.RiskCheckCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RiskReservationControllerTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final Instant EXPIRES = NOW.plusSeconds(120);

  @Mock private RiskReservationService service;

  private final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    RiskReservationController controller =
        new RiskReservationController(service, Clock.fixed(NOW, ZoneOffset.UTC));
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new RestExceptionHandler())
            .setMessageConverters(
                new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                    json))
            .build();
  }

  @Test
  void reserveReturnsLeaseStateAndExpiry() throws Exception {
    when(service.reserve(any(RiskCheckCommand.class)))
        .thenReturn(RiskReservationOutcome.approved(ReservationState.RESERVED, EXPIRES, List.of()));

    mvc.perform(
            post("/internal/v1/risk/reservations")
                .contentType("application/json")
                .content(json.writeValueAsBytes(request())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approved").value(true))
        .andExpect(jsonPath("$.reservationState").value("RESERVED"))
        .andExpect(jsonPath("$.expiresAt").value(EXPIRES.toString()));
  }

  @Test
  void changedPayloadReturnsDuplicateBetProblem() throws Exception {
    when(service.reserve(any(RiskCheckCommand.class)))
        .thenThrow(new ReservationConflictException("bet-1"));

    mvc.perform(
            post("/internal/v1/risk/reservations")
                .contentType("application/json")
                .content(json.writeValueAsBytes(request())))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errorCode").value("DUPLICATE_BET"));
  }

  @Test
  void nonPositiveStakeIsRejectedBeforeTheReservationService() throws Exception {
    String negative =
        """
        {
          "userId": "user-1",
          "betId": "bet-negative",
          "stake": {"amount": -100, "currency": "KRW"},
          "selectionIds": ["selection-1"]
        }
        """;

    mvc.perform(
            post("/internal/v1/risk/reservations")
                .contentType("application/json")
                .content(negative))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.detail").value("stake amount must be positive"));

    verify(service, never()).reserve(any(RiskCheckCommand.class));
  }

  @Test
  void commitIsNoContentAndExpiredCommitIsNotFound() throws Exception {
    mvc.perform(put("/internal/v1/risk/reservations/bet-1/commit"))
        .andExpect(status().isNoContent());

    doThrow(new ReservationNotFoundException("expired"))
        .when(service)
        .commit(eq("expired"), any(Instant.class));
    mvc.perform(put("/internal/v1/risk/reservations/expired/commit"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("RISK_RESERVATION_NOT_FOUND"));
  }

  @Test
  void releaseIsIdempotentExceptForCommittedReservation() throws Exception {
    mvc.perform(delete("/internal/v1/risk/reservations/missing")).andExpect(status().isNoContent());

    doThrow(new CommittedReservationReleaseException("committed"))
        .when(service)
        .release("committed", NOW);
    mvc.perform(delete("/internal/v1/risk/reservations/committed"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("RISK_RESERVATION_COMMITTED"));
  }

  private static RiskCheckRequest request() {
    return new RiskCheckRequest("user-1", "bet-1", Money.krw(100L), List.of("selection-1"));
  }
}
