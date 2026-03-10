package com.sportsbook.risk.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RiskPatternPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(EnablePatterns.class);

  @Test
  void bindsAllThreeRulesFromYaml() {
    runner
        .withPropertyValues(
            "risk.patterns.rapid-betting.enabled=true",
            "risk.patterns.rapid-betting.window-seconds=120",
            "risk.patterns.rapid-betting.max-bets=50",
            "risk.patterns.rapid-betting.action=BLOCK",
            "risk.patterns.sudden-stake-increase.enabled=true",
            "risk.patterns.sudden-stake-increase.multiplier-threshold=20",
            "risk.patterns.sudden-stake-increase.lookback-bets=20",
            "risk.patterns.repeated-same-selection.enabled=true",
            "risk.patterns.repeated-same-selection.window-hours=48",
            "risk.patterns.repeated-same-selection.max-count=10")
        .run(
            context -> {
              RiskPatternProperties props = context.getBean(RiskPatternProperties.class);

              assertThat(props.rapidBetting().enabled()).isTrue();
              assertThat(props.rapidBetting().windowSeconds()).isEqualTo(120);
              assertThat(props.rapidBetting().maxBets()).isEqualTo(50);
              assertThat(props.rapidBetting().action()).isEqualTo(PatternAction.BLOCK);

              assertThat(props.suddenStakeIncrease().multiplierThreshold()).isEqualTo(20);
              assertThat(props.suddenStakeIncrease().action()).isEqualTo(PatternAction.SUSPECT);

              assertThat(props.repeatedSameSelection().windowHours()).isEqualTo(48);
              assertThat(props.repeatedSameSelection().action()).isEqualTo(PatternAction.REVIEW);
            });
  }

  @Test
  void disablesAllRulesWhenYamlOmitted() {
    runner.run(
        context -> {
          RiskPatternProperties props = context.getBean(RiskPatternProperties.class);

          assertThat(props.rapidBetting().enabled()).isFalse();
          assertThat(props.suddenStakeIncrease().enabled()).isFalse();
          assertThat(props.repeatedSameSelection().enabled()).isFalse();
        });
  }

  @Test
  void rejectsEnabledRapidBettingWithZeroWindow() {
    runner
        .withPropertyValues(
            "risk.patterns.rapid-betting.enabled=true",
            "risk.patterns.rapid-betting.window-seconds=0",
            "risk.patterns.rapid-betting.max-bets=10")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("window-seconds");
            });
  }

  @Test
  void rejectsSuddenStakeIncreaseMultiplierBelowTwo() {
    runner
        .withPropertyValues(
            "risk.patterns.sudden-stake-increase.enabled=true",
            "risk.patterns.sudden-stake-increase.multiplier-threshold=1",
            "risk.patterns.sudden-stake-increase.lookback-bets=5")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("multiplier-threshold");
            });
  }

  @Test
  void skipsThresholdValidationWhenRuleIsDisabled() {
    // Operators sometimes leave invalid placeholders next to enabled=false; the binder must
    // accept them so disabling a rule is the same single-line yaml flip everywhere.
    runner
        .withPropertyValues(
            "risk.patterns.rapid-betting.enabled=false",
            "risk.patterns.rapid-betting.window-seconds=0",
            "risk.patterns.rapid-betting.max-bets=0")
        .run(
            context -> {
              RiskPatternProperties props = context.getBean(RiskPatternProperties.class);
              assertThat(props.rapidBetting().enabled()).isFalse();
              assertThat(props.rapidBetting().windowSeconds()).isZero();
            });
  }

  @EnableConfigurationProperties(RiskPatternProperties.class)
  static class EnablePatterns {}
}
