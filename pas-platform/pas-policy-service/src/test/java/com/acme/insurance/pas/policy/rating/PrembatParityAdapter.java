package com.acme.insurance.pas.policy.rating;

import com.acme.insurance.pas.parity.ParityAdapter;
import com.acme.insurance.pas.parity.ParityCase;
import com.acme.insurance.pas.parity.ParityFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Feeds PREMBAT fixture rows through {@link PremiumCalculator}. */
class PrembatParityAdapter implements ParityAdapter {

  private final PremiumCalculator calculator = new PremiumCalculator();

  @Override
  public String program() {
    return "prembat";
  }

  @Override
  public Map<String, String> run(ParityCase c) {
    PremiumCalculation r =
        calculator.rate(
            new PolicyTerm(c.text("policy_type"), c.date("effective_date"), c.date("expiry_date")));
    Map<String, String> out = new LinkedHashMap<>();
    out.put("coverage_seq", ParityFormat.integer(r.coverageSeq()));
    out.put("term_eff_date", ParityFormat.date(r.termEffectiveDate()));
    out.put("term_exp_date", ParityFormat.date(r.termExpiryDate()));
    out.put("base_rate", ParityFormat.decimal(r.baseRate(), 4));
    out.put("territory_premium", ParityFormat.money(r.territoryPremium()));
    out.put("class_premium", ParityFormat.money(r.classPremium()));
    out.put("mod_premium", ParityFormat.money(r.modifiedPremium()));
    out.put("tax_amt", ParityFormat.money(r.tax()));
    out.put("surcharge_amt", ParityFormat.money(r.surcharge()));
    out.put("total_premium", ParityFormat.money(r.total()));
    return out;
  }
}
