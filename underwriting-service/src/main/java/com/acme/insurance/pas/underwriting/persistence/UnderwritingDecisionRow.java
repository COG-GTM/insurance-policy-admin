package com.acme.insurance.pas.underwriting.persistence;

import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Row of {@code ACMEINS.UNDERWRITING_DECISIONS} (PK POLICY_NUMBER, DECISION_DATE). Only used as
 * the aggregate type of {@link UnderwritingRepository}; all statements are explicit SQL so they
 * match the DB2 statements in UNDWRT.cbl.
 */
@Table("UNDERWRITING_DECISIONS")
public record UnderwritingDecisionRow(
        @Id String policyNumber,
        LocalDate decisionDate,
        String decisionCode,
        Integer riskScore,
        String decisionReason,
        String underwriterId) {
}
