package com.acme.insurance.pas.policy.lifecycle;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SHADOW-route outputs in {@code pas_shadow} (ADR 0001 D5: no FKs to pas, no PII). */
@Repository
public class ShadowResultRepository {
  private final JdbcClient jdbc;

  public ShadowResultRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record RenewalResult(
      String policyNumber,
      LocalDate termExpiryDate,
      String outcome,
      LocalDate newEffectiveDate,
      LocalDate newExpiryDate,
      BigDecimal newPremium,
      BigDecimal rateChangePct,
      Integer renewalCount,
      Integer openClaims) {}

  public record EndorsementResult(
      String policyNumber,
      String endorsementType,
      LocalDate effectiveDate,
      String outcome,
      Integer endorsementSeq,
      BigDecimal premiumAdjustment,
      BigDecimal proRataFactor,
      BigDecimal totalPremiumAfter) {}

  public void recordRenewal(RenewalResult result) {
    jdbc.sql(
            """
            INSERT INTO pas_shadow.renewal_results (
                policy_number, term_expiry_date, outcome, new_effective_date, new_expiry_date,
                new_premium, rate_change_pct, renewal_count, open_claims
            ) VALUES (
                :policyNumber, :termExpiryDate, :outcome, :newEffectiveDate, :newExpiryDate,
                :newPremium, :rateChangePct, :renewalCount, :openClaims
            )
            """)
        .param("policyNumber", result.policyNumber())
        .param("termExpiryDate", result.termExpiryDate())
        .param("outcome", result.outcome())
        .param("newEffectiveDate", result.newEffectiveDate())
        .param("newExpiryDate", result.newExpiryDate())
        .param("newPremium", result.newPremium())
        .param("rateChangePct", result.rateChangePct())
        .param("renewalCount", result.renewalCount())
        .param("openClaims", result.openClaims())
        .update();
  }

  public void recordEndorsement(EndorsementResult result) {
    jdbc.sql(
            """
            INSERT INTO pas_shadow.endorsement_results (
                policy_number, endorsement_type, effective_date, outcome, endorsement_seq,
                premium_adjustment, pro_rata_factor, total_premium_after
            ) VALUES (
                :policyNumber, :endorsementType, :effectiveDate, :outcome, :endorsementSeq,
                :premiumAdjustment, :proRataFactor, :totalPremiumAfter
            )
            """)
        .param("policyNumber", result.policyNumber())
        .param("endorsementType", result.endorsementType())
        .param("effectiveDate", result.effectiveDate())
        .param("outcome", result.outcome())
        .param("endorsementSeq", result.endorsementSeq())
        .param("premiumAdjustment", result.premiumAdjustment())
        .param("proRataFactor", result.proRataFactor())
        .param("totalPremiumAfter", result.totalPremiumAfter())
        .update();
  }
}
