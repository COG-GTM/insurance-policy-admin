package com.acme.insurance.pas.policy.underwriting;

import com.acme.insurance.pas.underwriting.UnderwritingDecision;
import java.math.BigDecimal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ShadowUnderwritingRepository {
  private final JdbcClient jdbc;

  public ShadowUnderwritingRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(String policyNumber, UnderwritingDecision decision) {
    jdbc.sql(
            """
            INSERT INTO pas_shadow.underwriting_results (
                policy_number, decision_code, risk_score, decision_reasons, loss_ratio
            ) VALUES (
                :policyNumber, :decisionCode, :riskScore, CAST(:reasons AS text[]), :lossRatio
            )
            """)
        .param("policyNumber", policyNumber)
        .param("decisionCode", decision.code().name())
        .param("riskScore", decision.riskScore())
        .param("reasons", decision.reasons().toArray(String[]::new))
        .param(
            "lossRatio",
            decision.lossRatio() == null ? BigDecimal.ZERO.setScale(2) : decision.lossRatio())
        .update();
  }
}
