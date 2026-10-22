package com.acme.insurance.pas.policy.renewal;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code pas.policy_renewals}: one row per renewed term, the Q-17 idempotency key. */
@Repository
public class PolicyRenewalRepository {
  private final JdbcClient jdbc;

  public PolicyRenewalRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record RenewalRecord(
      String policyNumber,
      LocalDate priorExpiryDate,
      int renewalCount,
      LocalDate priorEffectiveDate,
      LocalDate newEffectiveDate,
      LocalDate newExpiryDate,
      BigDecimal priorPremium,
      BigDecimal newPremium,
      BigDecimal rateChangePct,
      int openClaims,
      String processedBy) {}

  public boolean exists(String policyNumber, LocalDate priorExpiryDate) {
    return jdbc.sql(
                "SELECT count(*) FROM pas.policy_renewals WHERE policy_number = :policyNumber AND prior_expiry_date = :priorExpiryDate")
            .param("policyNumber", policyNumber)
            .param("priorExpiryDate", priorExpiryDate)
            .query(Long.class)
            .single()
        > 0;
  }

  public void insert(RenewalRecord record) {
    jdbc.sql(
            """
            INSERT INTO pas.policy_renewals (
                policy_number, prior_expiry_date, renewal_count, prior_effective_date,
                new_effective_date, new_expiry_date, prior_premium, new_premium, rate_change_pct,
                open_claims, processed_by
            ) VALUES (
                :policyNumber, :priorExpiryDate, :renewalCount, :priorEffectiveDate,
                :newEffectiveDate, :newExpiryDate, :priorPremium, :newPremium, :rateChangePct,
                :openClaims, :processedBy
            )
            """)
        .param("policyNumber", record.policyNumber())
        .param("priorExpiryDate", record.priorExpiryDate())
        .param("renewalCount", record.renewalCount())
        .param("priorEffectiveDate", record.priorEffectiveDate())
        .param("newEffectiveDate", record.newEffectiveDate())
        .param("newExpiryDate", record.newExpiryDate())
        .param("priorPremium", record.priorPremium())
        .param("newPremium", record.newPremium())
        .param("rateChangePct", record.rateChangePct())
        .param("openClaims", record.openClaims())
        .param("processedBy", record.processedBy())
        .update();
  }
}
