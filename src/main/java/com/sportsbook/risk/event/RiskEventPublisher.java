package com.sportsbook.risk.event;

import com.sportsbook.protocol.event.Money;
import com.sportsbook.protocol.event.RiskLimitType;
import com.sportsbook.protocol.event.RiskLimitViolated;
import com.sportsbook.protocol.event.RiskPatternSuspected;
import com.sportsbook.protocol.event.RiskPatternType;
import com.sportsbook.risk.counter.LimitType;
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
 * Publishes the {@code risk.limit.violated} and {@code risk.pattern.suspected} Avro events that the
 * rest of the platform (admin-api dashboards, settlement audit, downstream alerting) consume.
 *
 * <p>Partition key is {@code userId} (ADR-0006 — same-key ordering inside a single user's stream),
 * payloads are Avro-encoded via {@link AvroCodec}.
 *
 * <p>The shared-protocol Avro {@link RiskLimitType} enum only covers {@code STAKE_DAILY /
 * OPEN_EXPOSURE / SELECTIONS_PER_MINUTE}. The internal {@link LimitType} additionally tracks {@code
 * STAKE_WEEKLY} and {@code STAKE_MONTHLY}, and {@code SINGLE_BET_MAX} is a per-bet threshold rather
 * than a sliding-window limit. Rejections for those internal-only types are logged for ops and
 * {@code risk_limit_violations_total} but are not published — the wire schema stays the source of
 * truth for the public catalogue and grows via the V2 Apicurio evolution path.
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
