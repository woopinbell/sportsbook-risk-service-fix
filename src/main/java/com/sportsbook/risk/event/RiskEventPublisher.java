package com.sportsbook.risk.event;

import com.sportsbook.protocol.event.Money;
import com.sportsbook.protocol.event.RiskLimitType;
import com.sportsbook.protocol.event.RiskLimitViolated;
import com.sportsbook.protocol.event.RiskPatternSuspected;
import com.sportsbook.protocol.event.RiskPatternType;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.service.LimitRejection;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 운영 화면, 정산 감사, 알림에서 사용하는 {@code risk.limit.violated}와 {@code risk.pattern.suspected} Avro 이벤트를
 * 발행합니다.
 *
 * <p>사용자별 순서를 유지하기 위해 {@code userId}를 파티션 키로 사용하며(ADR-0006), {@link AvroCodec}으로 직렬화합니다.
 *
 * <p>공통 계약의 {@link RiskLimitType}은 {@code STAKE_DAILY / OPEN_EXPOSURE / SELECTIONS_PER_MINUTE}만
 * 제공합니다. 내부에서만 사용하는 {@code STAKE_WEEKLY}, {@code STAKE_MONTHLY}, {@code SINGLE_BET_MAX} 거절은 로그와
 * {@code risk_limit_violations_total} 지표에만 남기고 이벤트로 발행하지 않습니다.
 */
@Component
public class RiskEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(RiskEventPublisher.class);

  private final KafkaTemplate<String, byte[]> kafka;
  private final EventTopics topics;

  public RiskEventPublisher(KafkaTemplate<String, byte[]> kafka, EventTopics topics) {
    this.kafka = kafka;
    this.topics = topics;
  }

  public void publishLimitViolated(String userId, LimitRejection rejection, Instant occurredAt) {
    Optional<RiskLimitType> wireType = mapLimitType(rejection.reason());
    if (wireType.isEmpty()) {
      log.debug(
          "Skipping risk.limit.violated publish for reason '{}' (not present in shared-protocol RiskLimitType)",
          rejection.reason());
      return;
    }
    Money requestedAmount =
        Money.newBuilder()
            .setAmount(rejection.requested())
            .setCurrency(rejection.currency() == null ? "" : rejection.currency().name())
            .build();
    RiskLimitViolated event =
        RiskLimitViolated.newBuilder()
            .setUserId(userId)
            .setLimitType(wireType.get())
            .setCurrentValue(rejection.current())
            .setLimitValue(rejection.limit())
            .setRequestedAmount(requestedAmount)
            .setOccurredAt(occurredAt)
            .build();
    kafka.send(topics.riskLimitViolated(), userId, AvroCodec.encode(event));
  }

  public void publishPatternSuspected(String userId, PatternMatch match, Instant occurredAt) {
    Optional<RiskPatternType> wireType = mapPatternRule(match.ruleName());
    if (wireType.isEmpty()) {
      log.warn("Skipping risk.pattern.suspected publish for unknown rule '{}'", match.ruleName());
      return;
    }
    Map<String, String> evidence = new HashMap<>();
    evidence.put("reason", match.reason());
    evidence.put("action", match.action().name());
    RiskPatternSuspected event =
        RiskPatternSuspected.newBuilder()
            .setUserId(userId)
            .setPatternType(wireType.get())
            .setEvidence(evidence)
            .setOccurredAt(occurredAt)
            .build();
    kafka.send(topics.riskPatternSuspected(), userId, AvroCodec.encode(event));
  }

  static Optional<RiskLimitType> mapLimitType(String internalReason) {
    if (internalReason == null) {
      return Optional.empty();
    }
    if (internalReason.startsWith("STAKE_DAILY")) {
      return Optional.of(RiskLimitType.STAKE_DAILY);
    }
    if (internalReason.startsWith("SELECTIONS_PER_MINUTE")) {
      return Optional.of(RiskLimitType.SELECTIONS_PER_MINUTE);
    }
    return Optional.empty();
  }

  static Optional<RiskPatternType> mapPatternRule(String ruleName) {
    if (ruleName == null) {
      return Optional.empty();
    }
    return switch (ruleName) {
      case "rapid-betting" -> Optional.of(RiskPatternType.RAPID_BETTING);
      case "sudden-stake-increase" -> Optional.of(RiskPatternType.SUDDEN_STAKE_INCREASE);
      case "repeated-same-selection" -> Optional.of(RiskPatternType.REPEATED_SAME_SELECTION);
      default -> Optional.empty();
    };
  }
}
