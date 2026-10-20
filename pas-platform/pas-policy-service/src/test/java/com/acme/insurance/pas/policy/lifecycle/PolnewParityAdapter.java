package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.common.domain.PolicyNumbers;
import com.acme.insurance.pas.parity.ParityAdapter;
import com.acme.insurance.pas.parity.ParityCase;
import com.acme.insurance.pas.parity.ParityFormat;
import com.acme.insurance.pas.policy.persistence.CoverageEntity;
import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * POLNEW fixture case → {@link NewBusinessRules} and the Q-27 policy-number formatter (the code
 * {@link NewBusinessService} runs). {@code writes} maps the COBOL side effects: POLICIES insert,
 * each COVERAGES insert, and the TDQ write, which the service replaces with the {@code
 * PolicyCreated} outbox event in the same transaction.
 */
class PolnewParityAdapter implements ParityAdapter {

  @Override
  public String program() {
    return "polnew";
  }

  @Override
  public Map<String, String> run(ParityCase c) {
    LocalDate today = c.date("as_of_date");
    CreatePolicyRequest request =
        new CreatePolicyRequest(
            blankToNull(c.text("holder_id")),
            blankToNull(c.text("policy_type")),
            c.date("effective_date"),
            c.date("expiry_date"),
            c.decimal("policy_limit"),
            c.decimal("total_premium"),
            c.decimal("deductible"),
            c.text("agent_code"),
            c.text("branch_code"));
    boolean holderFound = c.integer("holder_sqlcode") == 0;
    Optional<String> rejection = NewBusinessRules.validate(request, today, holderFound);

    Map<String, String> out = new LinkedHashMap<>();
    if (rejection.isPresent()) {
      out.put("error_msg", rejection.get());
      out.put("screens_sent", "TEXT");
      out.put("writes", "");
      out.put("policy_number", "");
      out.put("pol_status", "");
      out.put("pol_uw_status", "");
      out.put("pol_risk", "0");
      out.put("pol_api", "");
      out.put("pol_updated_by", "");
      out.put("pol_eff_dt", "");
      out.put("pol_exp_dt", "");
      out.put("pol_incept_dt", "");
      out.put("pol_limit", "0.00");
      out.put("cov_limit", "0.00");
      out.put("cov_deductible", "0.00");
      out.put("cov_premium", "0.00");
      return out;
    }
    String number = PolicyNumbers.forNewBusiness(c.integer("policy_seq"));
    PolicyEntity policy = NewBusinessRules.newPolicy(request, number, LocalDateTime.MIN);
    List<CoverageEntity> coverages = NewBusinessRules.defaultCoverages(policy);
    StringBuilder writes = new StringBuilder("POLICIES");
    for (CoverageEntity cov : coverages) {
      writes.append(
          String.format(
              Locale.ROOT, ";COV%03d:%s", cov.getId().getSequenceNum(), cov.getCoverageType()));
    }
    writes.append(";TDQ");
    CoverageEntity last = coverages.isEmpty() ? null : coverages.get(coverages.size() - 1);
    out.put("error_msg", "");
    out.put("screens_sent", "POLNCONF");
    out.put("writes", writes.toString());
    out.put("policy_number", policy.getPolicyNumber());
    out.put("pol_status", policy.getPolicyStatus());
    out.put("pol_uw_status", policy.getUwStatus());
    out.put("pol_risk", ParityFormat.integer(policy.getRiskScore()));
    out.put("pol_api", policy.getApiFlag());
    out.put("pol_updated_by", policy.getUpdatedBy());
    out.put("pol_eff_dt", ParityFormat.date(policy.getEffectiveDate()));
    out.put("pol_exp_dt", ParityFormat.date(policy.getExpiryDate()));
    out.put("pol_incept_dt", ParityFormat.date(policy.getInceptionDate()));
    out.put("pol_limit", ParityFormat.money(policy.getCoverageLimit()));
    out.put("cov_limit", money(last == null ? null : last.getCoverageLimit()));
    out.put("cov_deductible", money(last == null ? null : last.getDeductible()));
    out.put("cov_premium", money(last == null ? null : last.getPremium()));
    return out;
  }

  private static String money(BigDecimal value) {
    return ParityFormat.money(value == null ? BigDecimal.ZERO : value);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
