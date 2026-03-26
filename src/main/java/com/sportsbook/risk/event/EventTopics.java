package com.sportsbook.risk.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka topic names mirrored from {@code application.yml}. Keeps the constants out of code so
 * orchestration can override topic prefixes per environment without rebuilding the service.
 */
@ConfigurationProperties(prefix = "topics")
public record EventTopics(String betPlaced, String riskLimitViolated, String riskPatternSuspected) {

  public EventTopics {
    if (betPlaced == null || betPlaced.isBlank()) {
      betPlaced = "bet.placed.v1";
    }
    if (riskLimitViolated == null || riskLimitViolated.isBlank()) {
      riskLimitViolated = "risk.limit.violated";
    }
    if (riskPatternSuspected == null || riskPatternSuspected.isBlank()) {
      riskPatternSuspected = "risk.pattern.suspected";
    }
  }
}
