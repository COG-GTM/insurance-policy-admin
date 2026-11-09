package com.acme.insurance.pas.underwriting.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.underwriting.copybook.PolicyRecord;
import com.acme.insurance.pas.underwriting.messaging.UnderwritingNotificationPublisher;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** 6000-WRITE-DECISION and 7000-SEND-MQ-NOTIFICATION side effects. */
@SpringBootTest
class UnderwritingSideEffectsTest {

    static final List<PolicyRecord> published = new ArrayList<>();
    static volatile boolean failPublish;

    @TestConfiguration
    static class CapturingPublisher {
        @Bean
        @Primary
        UnderwritingNotificationPublisher capturing() {
            return (queue, record) -> {
                assertThat(queue).isEqualTo("ACME.PAS.UNDERWRITING.DECISION");
                if (failPublish) {
                    throw new IllegalStateException("QIDERR");
                }
                published.add(record);
            };
        }
    }

    @Autowired
    UnderwritingDecisionService service;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        published.clear();
        failPublish = false;
        jdbc.update("DELETE FROM ACMEINS.CLAIMS");
        jdbc.update("DELETE FROM ACMEINS.UNDERWRITING_DECISIONS");
        jdbc.update("DELETE FROM ACMEINS.POLICIES");
        jdbc.update("DELETE FROM ACMEINS.POLICY_HOLDERS");
        jdbc.update("INSERT INTO ACMEINS.POLICY_HOLDERS (CUST_ID, CUST_TYPE, CREDIT_SCORE, RISK_TIER) VALUES ('C1', 'C', 0, 'U')");
        jdbc.update("""
                INSERT INTO ACMEINS.POLICIES (POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS, EFFECTIVE_DATE, EXPIRY_DATE,
                       POLICYHOLDER_ID, BRANCH_CODE, TOTAL_PREMIUM, COVERAGE_LIMIT, UW_STATUS)
                VALUES ('POL-SE000001', 'COM', 'PN', DATE '2026-12-01', DATE '2027-12-01', 'C1', 'B001', 1000.00,
                        100000.00, 'PN')
                """);
    }

    @Test
    void writesAuditRowAndUpdatesPolicy() {
        UnderwritingResult result = service.decide("POL-SE000001");

        assertThat(result).isInstanceOf(UnderwritingResult.Decision.class);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ACMEINS.UNDERWRITING_DECISIONS");
        assertThat(row.get("POLICY_NUMBER")).isEqualTo("POL-SE000001");
        assertThat(row.get("DECISION_DATE").toString()).isEqualTo("2026-11-09");
        assertThat(row.get("DECISION_CODE")).isEqualTo("RM");
        assertThat(((Number) row.get("RISK_SCORE")).intValue()).isEqualTo(600);
        assertThat(row.get("DECISION_REASON").toString().stripTrailing()).isEqualTo("REFERRED TO UW MANAGER");
        assertThat(row.get("UNDERWRITER_ID").toString().strip()).isEqualTo("SYSTEM");
        assertThat(row.get("CREATED_TIMESTAMP")).isNotNull();

        Map<String, Object> policy = jdbc.queryForMap(
                "SELECT UW_STATUS, RISK_SCORE, UPDATED_BY, LAST_UPDATED FROM ACMEINS.POLICIES WHERE POLICY_NUMBER = 'POL-SE000001'");
        assertThat(policy.get("UW_STATUS")).isEqualTo("RM");
        assertThat(((Number) policy.get("RISK_SCORE")).intValue()).isEqualTo(600);
        assertThat(policy.get("UPDATED_BY").toString().strip()).isEqualTo("UNDWRT");
        assertThat(policy.get("LAST_UPDATED")).isNotNull();
    }

    @Test
    void publishesPolicyRecordWithDecisionToQueue() {
        service.decide("POL-SE000001");

        assertThat(published).singleElement().satisfies(p -> {
            assertThat(p.policyNumber()).isEqualTo("POL-SE000001");
            assertThat(p.uwStatus()).isEqualTo("RM");
            assertThat(p.riskScore()).isEqualTo(600);
            assertThat(p.branchCode()).isEqualTo("B001");
        });
    }

    @Test
    void secondDecisionSameDayKeepsFirstAuditRowButStillUpdatesPolicy() {
        service.decide("POL-SE000001");
        jdbc.update("UPDATE ACMEINS.POLICY_HOLDERS SET CREDIT_SCORE = 800, RISK_TIER = 'S'");

        UnderwritingResult second = service.decide("POL-SE000001");

        assertThat(second).isInstanceOfSatisfying(UnderwritingResult.Decision.class,
                d -> assertThat(d.decisionCode()).isEqualTo(DecisionCode.AP));
        assertThat(jdbc.queryForList("SELECT DECISION_CODE FROM ACMEINS.UNDERWRITING_DECISIONS", String.class))
                .containsExactly("RM");
        assertThat(jdbc.queryForObject("SELECT UW_STATUS FROM ACMEINS.POLICIES WHERE POLICY_NUMBER = 'POL-SE000001'",
                String.class)).isEqualTo("AP");
    }

    @Test
    void queueFailureDoesNotChangeTheDecisionOrRollBack() {
        failPublish = true;

        UnderwritingResult result = service.decide("POL-SE000001");

        assertThat(result).isInstanceOf(UnderwritingResult.Decision.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACMEINS.UNDERWRITING_DECISIONS", Integer.class)).isEqualTo(1);
    }

    @Test
    void notFoundWritesNothing() {
        UnderwritingResult result = service.decide("POL-MISSING1");

        assertThat(result).isEqualTo(new UnderwritingResult.Error(UnderwritingResult.Error.Kind.NOT_FOUND,
                "POLICY/CUSTOMER DATA NOT FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACMEINS.UNDERWRITING_DECISIONS", Integer.class)).isZero();
        assertThat(published).isEmpty();
    }

    @Test
    void accumulationReasonIsBuiltThenOverwrittenBy5000() {
        UnderwritingDecisionService.WorkArea ws = new UnderwritingDecisionService.WorkArea();
        ws.currentDate = LocalDate.of(2026, 11, 9);
        ws.policy = PolicyRecord.fromUnderwritingSelect("P1", "COM", java.math.BigDecimal.ONE,
                java.math.BigDecimal.ONE, "B9");
        jdbc.update("""
                INSERT INTO ACMEINS.POLICIES (POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS, EFFECTIVE_DATE, EXPIRY_DATE,
                       POLICYHOLDER_ID, BRANCH_CODE, TOTAL_PREMIUM, COVERAGE_LIMIT)
                VALUES ('POL-SE000002', 'COM', 'AC', DATE '2026-12-01', DATE '2027-12-01', 'C1', 'B9', 1.00, 600000000.00)
                """);

        service.checkAccumulation(ws);
        assertThat(ws.decisionReason).isEqualTo("ACCUMULATION LIMIT EXCEEDED FOR BRANCH B9");
        assertThat(ws.riskScore).isEqualTo(300);

        service.renderDecision(ws);
        assertThat(ws.decisionReason).isEqualTo("REFERRED TO SENIOR UNDERWRITER");
    }
}
