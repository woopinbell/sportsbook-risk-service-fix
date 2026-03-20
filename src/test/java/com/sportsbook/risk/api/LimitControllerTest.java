package com.sportsbook.risk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.counter.LimitType;
import com.sportsbook.risk.limit.LimitOverrideStore;
import com.sportsbook.risk.limit.LimitResolver;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LimitControllerTest {

  private final ObjectMapper json = new ObjectMapper();

  @Mock private LimitOverrideStore store;
  @Mock private LimitResolver resolver;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    LimitController controller = new LimitController(store, resolver);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new RestExceptionHandler())
            .build();
  }

  @Test
  void getReturnsEffectiveLimitsWithSourceLabel() throws Exception {
    when(resolver.resolveUser(anyString(), ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(1_000_000L);
    when(store.findUserOverride(anyString(), ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(Optional.empty());
    when(store.findUserOverride("u-1", LimitType.STAKE_DAILY, Currency.KRW))
        .thenReturn(Optional.of(5_000_000L));

    String body =
        mvc.perform(get("/internal/v1/risk/limits/u-1"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // The matched STAKE_DAILY/KRW entry should be marked OVERRIDE; the rest land on POLICY.
    assertThat(body).contains("\"userId\":\"u-1\"");
    assertThat(body)
        .contains(
            "\"limitType\":\"STAKE_DAILY\",\"currency\":\"KRW\",\"value\":5000000,\"source\":\"OVERRIDE\"");
    assertThat(body).contains("\"source\":\"POLICY\"");
  }

  @Test
  void patchSetsOverride() throws Exception {
    LimitUpdateRequest req =
        new LimitUpdateRequest(LimitType.STAKE_DAILY, Currency.KRW, 7_500_000L);

    mvc.perform(
            patch("/internal/v1/risk/limits/u-1")
                .contentType("application/json")
                .content(json.writeValueAsBytes(req)))
        .andExpect(status().isNoContent());

    verify(store).setUserOverride("u-1", LimitType.STAKE_DAILY, Currency.KRW, 7_500_000L);
  }

  @Test
  void patchSelectionsPerMinuteNormalisesCurrencyToKrw() throws Exception {
    LimitUpdateRequest req = new LimitUpdateRequest(LimitType.SELECTIONS_PER_MINUTE, null, 50L);

    mvc.perform(
            patch("/internal/v1/risk/limits/u-1")
                .contentType("application/json")
                .content(json.writeValueAsBytes(req)))
        .andExpect(status().isNoContent());

    verify(store).setUserOverride("u-1", LimitType.SELECTIONS_PER_MINUTE, Currency.KRW, 50L);
  }

  @Test
  void patchStakeWithoutCurrencyReturnsRfc7807() throws Exception {
    LimitUpdateRequest req = new LimitUpdateRequest(LimitType.STAKE_DAILY, null, 1_000_000L);

    mvc.perform(
            patch("/internal/v1/risk/limits/u-1")
                .contentType("application/json")
                .content(json.writeValueAsBytes(req)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

    verify(store, never())
        .setUserOverride(anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(), anyLong());
  }

  @Test
  void deleteClearsOverride() throws Exception {
    mvc.perform(delete("/internal/v1/risk/limits/u-1/STAKE_DAILY/KRW"))
        .andExpect(status().isNoContent());

    verify(store).clearUserOverride("u-1", LimitType.STAKE_DAILY, Currency.KRW);
  }
}
