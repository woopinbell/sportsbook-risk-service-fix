package com.sportsbook.risk.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sportsbook.protocol.event.RiskLimitType;
import com.sportsbook.protocol.event.RiskLimitViolated;
import com.sportsbook.protocol.event.RiskPatternSuspected;
import com.sportsbook.protocol.event.RiskPatternType;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.risk.pattern.PatternMatch;
import com.sportsbook.risk.policy.PatternAction;
import com.sportsbook.risk.service.LimitRejection;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class RiskEventPublisherTest {

  private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");
  private static final EventTopics TOPICS =
      new EventTopics("bet.placed.v1", "risk.limit.violated", "risk.pattern.suspected");

  @Mock private KafkaTemplate<String, byte[]> kafka;

  @Test
  void publishesLimitViolatedForKnownWireType() {
    RiskEventPublisher publisher = new RiskEventPublisher(kafka, TOPICS);
    LimitRejection rejection =
        new LimitRejection(
            "STAKE_DAILY_LIMIT_EXCEEDED", Currency.KRW, 950_000L, 1_000_000L, 100_000L, "BLOCK");

    publisher.publishLimitViolated("u-1", rejection, NOW);

    ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
    verify(kafka).send(eq("risk.limit.violated"), eq("u-1"), payload.capture());
    RiskLimitViolated decoded = AvroCodec.decode(payload.getValue(), RiskLimitViolated.class);
    assertThat(decoded.getLimitType()).isEqualTo(RiskLimitType.STAKE_DAILY);
    assertThat(decoded.getCurrentValue()).isEqualTo(950_000L);
    assertThat(decoded.getLimitValue()).isEqualTo(1_000_000L);
    assertThat(decoded.getRequestedAmount().getAmount()).isEqualTo(100_000L);
    assertThat(decoded.getRequestedAmount().getCurrency()).hasToString("KRW");
  }

  @Test
  void skipsLimitViolatedForInternalOnlyType() {
    RiskEventPublisher publisher = new RiskEventPublisher(kafka, TOPICS);
    LimitRejection rejection =
        new LimitRejection(
            "STAKE_WEEKLY_LIMIT_EXCEEDED", Currency.KRW, 4_500_000L, 5_000_000L, 600_000L, "BLOCK");

    publisher.publishLimitViolated("u-1", rejection, NOW);

    verify(kafka, never()).send(any(), any(), any(byte[].class));
  }

  @Test
  void publishesPatternSuspectedWithReasonAndAction() {
    RiskEventPublisher publisher = new RiskEventPublisher(kafka, TOPICS);
    PatternMatch match = new PatternMatch("rapid-betting", PatternAction.SUSPECT, "30 in 60s");

    publisher.publishPatternSuspected("u-1", match, NOW);

    ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
    verify(kafka).send(eq("risk.pattern.suspected"), eq("u-1"), payload.capture());
    RiskPatternSuspected decoded = AvroCodec.decode(payload.getValue(), RiskPatternSuspected.class);
    assertThat(decoded.getPatternType()).isEqualTo(RiskPatternType.RAPID_BETTING);
    assertThat(decoded.getEvidence().get("reason")).hasToString("30 in 60s");
    assertThat(decoded.getEvidence().get("action")).hasToString("SUSPECT");
  }

  @Test
  void skipsPatternSuspectedForUnknownRule() {
    RiskEventPublisher publisher = new RiskEventPublisher(kafka, TOPICS);
    PatternMatch match = new PatternMatch("brand-new-rule", PatternAction.SUSPECT, "unknown");

    publisher.publishPatternSuspected("u-1", match, NOW);

    verify(kafka, never()).send(any(), any(), any(byte[].class));
  }
}
