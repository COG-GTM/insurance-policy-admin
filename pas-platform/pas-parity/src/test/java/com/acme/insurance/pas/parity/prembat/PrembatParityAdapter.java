package com.acme.insurance.pas.parity.prembat;

import com.acme.insurance.pas.parity.ParityAdapter;
import com.acme.insurance.pas.rating.PremiumBreakdown;
import com.acme.insurance.pas.rating.PremiumCalculator;
import com.acme.insurance.pas.rating.RatingRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

final class PrembatParityAdapter
    implements ParityAdapter<PrembatParityAdapter.Input, PrembatParityAdapter.Output> {
  private static final DateTimeFormatter COBOL_DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private final PremiumCalculator calculator;

  PrembatParityAdapter(PremiumCalculator calculator) {
    this.calculator = calculator;
  }

  @Override
  public String program() {
    return "PREMBAT";
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public Output run(Input input) {
    LocalDate currentDate = LocalDate.parse(input.currentDate(), COBOL_DATE);
    List<PremiumRow> premiums = new ArrayList<>();
    int policiesRead = 0;
    int policiesUpdated = 0;
    int policiesError = 0;
    for (Policy policy : input.policies()) {
      policiesRead++;
      PremiumBreakdown breakdown =
          calculator.calculate(new RatingRequest(policy.policyType(), currentDate, null, null));
      if (policy.insertSqlcode() == 0) {
        premiums.add(
            new PremiumRow(
                policy.policyNumber(),
                1,
                policy.effectiveDate(),
                policy.expiryDate(),
                breakdown.premiumRecordBaseRate(),
                breakdown.taxAmount(),
                breakdown.surchargeAmount(),
                breakdown.totalPremium(),
                input.currentDate(),
                "PREMBAT"));
        policiesUpdated++;
      } else {
        policiesError++;
        break;
      }
    }
    return new Output(
        premiums,
        List.of(
            new JobEndRow(
                policiesError > 0 ? 4 : 0, policiesRead, policiesUpdated, policiesError)));
  }

  record Input(String currentDate, List<Policy> policies) {}

  record Policy(
      String policyNumber,
      String policyType,
      BigDecimal totalPremium,
      BigDecimal deductible,
      BigDecimal coverageLimit,
      String effectiveDate,
      String expiryDate,
      int insertSqlcode) {}

  record Output(List<PremiumRow> premium, List<JobEndRow> jobEnd) {}

  record PremiumRow(
      String policyNumber,
      int coverageSeq,
      String termEffectiveDate,
      String termExpiryDate,
      BigDecimal baseRate,
      BigDecimal taxAmount,
      BigDecimal surchargeAmount,
      BigDecimal totalPremium,
      String calcDate,
      String calcBy) {}

  record JobEndRow(int returnCode, int policiesRead, int policiesUpdated, int policiesError) {}
}
