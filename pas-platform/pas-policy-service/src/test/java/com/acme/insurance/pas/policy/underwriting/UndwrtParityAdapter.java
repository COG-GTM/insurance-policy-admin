package com.acme.insurance.pas.policy.underwriting;

import com.acme.insurance.pas.parity.ParityAdapter;
import com.acme.insurance.pas.parity.ParityCase;
import com.acme.insurance.pas.parity.ParityFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** UNDWRT fixture case → {@link UnderwritingRiskScorer} (the production scoring code). */
class UndwrtParityAdapter implements ParityAdapter {

  @Override
  public String program() {
    return "undwrt";
  }

  @Override
  public Map<String, String> run(ParityCase c) {
    Map<String, String> out = new LinkedHashMap<>();
    if (c.integer("select_sqlcode") != 0) {
      // POLICIES JOIN POLICY_HOLDERS found nothing: the service throws this exception (422).
      out.put("error_msg", UnderwritingDataIncompleteException.MESSAGE);
      out.put("screens_sent", "TEXT");
      out.put("risk_score", "0");
      out.put("decision", "");
      out.put("decision_reason", "");
      out.put("loss_ratio", "0.00");
      out.put("policy_risk_score", "0");
      out.put("policy_uw_status", "");
      return out;
    }
    RiskAssessment a =
        UnderwritingRiskScorer.assess(
            new RiskInputs(
                c.decimal("total_premium"),
                c.decimal("policy_limit"),
                c.text("branch_code"),
                (int) c.integer("credit_score"),
                c.text("risk_tier"),
                c.integer("claim_count_5y"),
                c.decimal("total_incurred_5y"),
                c.decimal("branch_accum_limit")));
    out.put("error_msg", "");
    out.put("screens_sent", "UWRTRES");
    out.put("risk_score", ParityFormat.integer(a.riskScore()));
    out.put("decision", a.decision().code());
    out.put("decision_reason", a.decisionReason());
    out.put("loss_ratio", ParityFormat.money(a.lossRatio()));
    out.put("policy_risk_score", ParityFormat.integer(a.policyRiskScore()));
    out.put("policy_uw_status", a.decision().code());
    return out;
  }
}
