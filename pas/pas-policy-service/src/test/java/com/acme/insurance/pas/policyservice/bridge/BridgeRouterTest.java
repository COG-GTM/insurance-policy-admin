package com.acme.insurance.pas.policyservice.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.UnderwritingDecision;
import com.acme.insurance.pas.domain.copybook.PolicyRecordCodec;
import com.acme.insurance.pas.events.PasEvent;
import com.acme.insurance.pas.events.PasEventType;
import com.acme.insurance.pas.events.PasEvents;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BridgeRouterTest {

  private static Policy policy(Integer renewalCount, Integer riskScore) {
    return new Policy(
        "POL-00000001",
        "HO",
        "AC",
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2027, 1, 1),
        "C000000001",
        "AG0001",
        "BR01",
        new BigDecimal("1200.00"),
        new BigDecimal("500.00"),
        new BigDecimal("250000.00"),
        LocalDate.of(2020, 1, 1),
        renewalCount,
        "RF",
        riskScore,
        "N",
        "Y",
        LocalDateTime.of(2026, 1, 1, 9, 30),
        "PASAPI");
  }

  @Test
  void scoresAboveNineNineNineKeepTheirLastThreeDigitsLikeTheCobolMove() {
    PolicyRecordCodec codec = new PolicyRecordCodec();
    byte[] record = codec.encode(BridgeRouter.legacyView(policy(1004, 1450)));
    assertThat(record).hasSize(125);
    Policy decoded = codec.decode(record);
    assertThat(decoded.riskScore()).isEqualTo(450);
    assertThat(decoded.renewalCount()).isEqualTo(4);
  }

  @Test
  void valuesThatFitAndNullsAreUnchanged() {
    assertThat(BridgeRouter.legacyView(policy(3, 999)).riskScore()).isEqualTo(999);
    assertThat(BridgeRouter.legacyView(policy(null, null)))
        .extracting(Policy::riskScore, Policy::renewalCount)
        .containsOnlyNulls();
  }

  private static BridgeProperties properties() {
    return new BridgeProperties(
        true,
        "pas-legacy-bridge",
        "pas.bridge.filenet.document-trigger",
        "pas.bridge.mq.underwriting-request",
        "pas.bridge.mq.underwriting-decision",
        "pas.events.dlt",
        3,
        Duration.ofSeconds(1),
        Duration.ofSeconds(10),
        "pas.bridge.retry");
  }

  @Test
  void anUnderwritingDecisionGoesToTheDecisionQueueWithItsReferralHeaders() {
    Policy policy = policy(2, 1450);
    PasEvent event =
        new PasEvents(Clock.systemUTC())
            .underwritingDecided(
                new UnderwritingDecision(
                    null,
                    policy.policyNumber(),
                    LocalDate.of(2026, 11, 5),
                    "RS",
                    1450,
                    "REFERRED TO SENIOR UNDERWRITER",
                    "SYSTEM",
                    null,
                    null,
                    null),
                "it-uwd");

    List<BridgeMessage> messages =
        new BridgeRouter(properties(), n -> Optional.of(policy))
            .route(PasEventType.UNDERWRITING_DECIDED.topic(), PasEvents.toJson(event));

    assertThat(messages).hasSize(1);
    BridgeMessage m = messages.get(0);
    assertThat(m.topic()).isEqualTo("pas.bridge.mq.underwriting-decision");
    assertThat(m.key()).isEqualTo(policy.policyNumber());
    assertThat(m.headers())
        .containsEntry("eventId", event.eventId().toString())
        .containsEntry("eventType", "pas.underwriting.decided")
        .containsEntry("decisionCode", "RS")
        .containsEntry("referral", "true")
        .doesNotContainKey("documentType");
    Policy decoded = new PolicyRecordCodec().decode(m.value());
    assertThat(decoded.policyNumber().trim()).isEqualTo(policy.policyNumber());
    assertThat(decoded.riskScore()).isEqualTo(450);
  }
}
