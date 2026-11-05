package com.acme.insurance.pas.policyservice.underwriting;

import com.acme.insurance.pas.underwriting.UnderwritingRules;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pas.underwriting.*}: decision thresholds, branch accumulation limit and claims window.
 * Defaults are the values UNDWRT hardcodes; changing them is an Underwriting decision.
 */
@ConfigurationProperties("pas.underwriting")
public record UnderwritingProperties(
    @DefaultValue("300") int referSeniorFrom,
    @DefaultValue("600") int referManagerFrom,
    @DefaultValue("800") int declineFrom,
    @DefaultValue("500000000.00") BigDecimal branchAccumulationLimit,
    @DefaultValue("5") int lossHistoryYears) {

  public UnderwritingRules rules() {
    return new UnderwritingRules(
        referSeniorFrom, referManagerFrom, declineFrom, branchAccumulationLimit, lossHistoryYears);
  }
}
