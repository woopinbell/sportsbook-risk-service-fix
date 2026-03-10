package com.sportsbook.risk.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.sportsbook.protocol.value.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RiskLimitPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(EnableLimits.class);

  @Test
  void bindsDefaultsWhenYamlOmitsEverything() {
    runner.run(
        context -> {
          RiskLimitProperties props = context.getBean(RiskLimitProperties.class);

          assertThat(props.stakeDaily(Currency.KRW)).isEqualTo(1_000_000L);
          assertThat(props.stakeDaily(Currency.USD)).isEqualTo(1_000L);
          assertThat(props.stakeWeekly(Currency.KRW)).isEqualTo(5_000_000L);
          assertThat(props.stakeMonthly(Currency.USD)).isEqualTo(20_000L);
          assertThat(props.singleBetMax(Currency.KRW)).isEqualTo(500_000L);
          assertThat(props.openExposure(Currency.USD)).isEqualTo(2_000L);
          assertThat(props.selectionsPerMinute()).isEqualTo(30);
        });
  }

  @Test
  void bindsOverridesFromYaml() {
    runner
        .withPropertyValues(
            "risk.limits.stake-daily.KRW=2500000",
            "risk.limits.stake-daily.USD=2500",
            "risk.limits.selections-per-minute=100")
        .run(
            context -> {
              RiskLimitProperties props = context.getBean(RiskLimitProperties.class);

              assertThat(props.stakeDaily(Currency.KRW)).isEqualTo(2_500_000L);
              assertThat(props.stakeDaily(Currency.USD)).isEqualTo(2_500L);
              assertThat(props.selectionsPerMinute()).isEqualTo(100);
              // Unspecified groups still fall back to defaults.
              assertThat(props.stakeWeekly(Currency.KRW)).isEqualTo(5_000_000L);
            });
  }

  @Test
  void fillsMissingCurrencyWithDefault() {
    runner
        .withPropertyValues("risk.limits.stake-daily.KRW=3000000")
        .run(
            context -> {
              RiskLimitProperties props = context.getBean(RiskLimitProperties.class);

              assertThat(props.stakeDaily(Currency.KRW)).isEqualTo(3_000_000L);
              // USD was not in yaml — defaultIfAbsent kicks in so callers never see a null map.
              assertThat(props.stakeDaily(Currency.USD)).isEqualTo(1_000L);
            });
  }

  @Test
  void rejectsNegativeAmount() {
    runner
        .withPropertyValues("risk.limits.stake-daily.KRW=-1")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("must be non-negative");
            });
  }

  @Test
  void fallsBackOnNonPositiveSelectionsPerMinute() {
    runner
        .withPropertyValues("risk.limits.selections-per-minute=0")
        .run(
            context -> {
              RiskLimitProperties props = context.getBean(RiskLimitProperties.class);
              assertThat(props.selectionsPerMinute()).isEqualTo(30);
            });
  }

  @EnableConfigurationProperties(RiskLimitProperties.class)
  static class EnableLimits {}
}
