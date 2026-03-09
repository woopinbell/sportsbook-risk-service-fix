package com.sportsbook.risk;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Entry point for the risk-service. Enables {@code @ConfigurationProperties} scanning so the policy
 * records bind without explicit registration, and turns on Spring Kafka annotation processing for
 * the {@code bet.placed} listener. Also exposes a UTC {@link Clock} bean so sliding-window counters
 * and pattern rules can be swapped onto a fixed clock in tests.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableKafka
public class RiskServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(RiskServiceApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
