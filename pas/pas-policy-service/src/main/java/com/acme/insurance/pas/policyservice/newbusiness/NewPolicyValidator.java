package com.acme.insurance.pas.policyservice.newbusiness;

import com.acme.insurance.pas.policyservice.newbusiness.ApiError.Violation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * POLNEW {@code 3000-VALIDATE-INPUT}. Checks run in COBOL order and the legacy message is the last
 * failing one (quirk Q-11), so precedence is: holder not found > limit not positive > effective
 * date in the past > type required > holder id required. The holder lookup runs even when earlier
 * checks failed, as in the COBOL; a blank holder id is "not found". Field-format problems (widths,
 * decimal places) are reported separately and answered 400 before any business check.
 */
final class NewPolicyValidator {

  static final String HOLDER_REQUIRED = "POLICYHOLDER ID IS REQUIRED";
  static final String TYPE_REQUIRED = "POLICY TYPE IS REQUIRED";
  static final String DATE_IN_PAST = "EFFECTIVE DATE CANNOT BE IN PAST";
  static final String LIMIT_ZERO = "POLICY LIMIT MUST BE GREATER THAN ZERO";
  static final String HOLDER_NOT_FOUND = "POLICYHOLDER NOT FOUND IN SYSTEM";
  static final String INVALID_INPUT = "INVALID INPUT";

  private NewPolicyValidator() {}

  /** Width and scale problems, mirroring the map fields and DB2 columns. */
  static List<Violation> format(NewPolicyRequest r) {
    List<Violation> v = new ArrayList<>();
    width(v, "policyType", r.policyType(), 3);
    width(v, "policyholderId", r.policyholderId(), 10);
    width(v, "agentCode", r.agentCode(), 6);
    width(v, "branchCode", r.branchCode(), 4);
    money(v, "totalPremium", r.totalPremium(), 11);
    money(v, "deductible", r.deductible(), 9);
    money(v, "coverageLimit", r.coverageLimit(), 13);
    return v;
  }

  /** Business checks in COBOL order; the last entry carries the legacy message. */
  static List<Violation> business(
      NewPolicyRequest r, LocalDate today, Predicate<String> holderExists) {
    List<Violation> v = new ArrayList<>();
    if (blank(r.policyholderId())) {
      v.add(new Violation("policyholderId", HOLDER_REQUIRED));
    }
    if (blank(r.policyType())) {
      v.add(new Violation("policyType", TYPE_REQUIRED));
    }
    if (r.effectiveDate() == null || r.effectiveDate().isBefore(today)) {
      v.add(new Violation("effectiveDate", DATE_IN_PAST));
    }
    if (r.coverageLimit() == null || r.coverageLimit().signum() <= 0) {
      v.add(new Violation("coverageLimit", LIMIT_ZERO));
    }
    if (blank(r.policyholderId()) || !holderExists.test(r.policyholderId())) {
      v.add(new Violation("policyholderId", HOLDER_NOT_FOUND));
    }
    return v;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static void width(List<Violation> v, String field, String value, int max) {
    if (value != null && value.length() > max) {
      v.add(new Violation(field, "must be at most " + max + " characters"));
    }
  }

  private static void money(List<Violation> v, String field, BigDecimal value, int precision) {
    if (value == null) {
      return;
    }
    if (value.stripTrailingZeros().scale() > 2) {
      v.add(new Violation(field, "must have at most 2 decimal places"));
    } else if (value.setScale(2).unscaledValue().abs().toString().length() > precision) {
      v.add(new Violation(field, "does not fit DECIMAL(" + precision + ",2)"));
    }
  }
}
