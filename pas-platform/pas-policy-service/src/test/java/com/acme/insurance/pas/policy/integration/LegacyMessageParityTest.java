package com.acme.insurance.pas.policy.integration;

import com.acme.insurance.pas.common.domain.PolicyNumbers;
import com.acme.insurance.pas.common.domain.PolicyRecord;
import com.acme.insurance.pas.parity.ParityAdapter;
import com.acme.insurance.pas.parity.ParityCase;
import com.acme.insurance.pas.parity.ParityHarness;
import com.acme.insurance.pas.policy.lifecycle.CreatePolicyRequest;
import com.acme.insurance.pas.policy.lifecycle.NewBusinessRules;
import com.acme.insurance.pas.policy.persistence.CopybookMapping;
import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * AB-528 byte goldens: the 125-byte {@code POLICY-RECORD} message each CICS transaction wrote
 * (captured by GnuCOBOL in {@code parity/programs/<prog>-msg/}) equals {@link LegacyPolicyMessages}
 * applied to the policy row the Java service commits at that version. The harness runs in ASCII, so
 * the comparison uses US-ASCII; production encodes the same layout in IBM037. The row deliberately
 * carries values in fields the program never populates (holder, agent, inception, updated-by...) to
 * prove the bridge leaves them out exactly as CICS did.
 */
class LegacyMessageParityTest {

  private static final HexFormat HEX = HexFormat.of().withUpperCase();
  private static final DateTimeFormatter YYYYMMDD =
      DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
  private static final int DATE_DIGITS = 8;
  private static final int NUMBER_LENGTH = 12;

  @Test
  void polnewUnderwritingRequestMessage() {
    ParityHarness.assertParity(new PolnewMessage());
  }

  @Test
  void polrnwRenewalMessage() {
    ParityHarness.assertParity(new PolrnwMessage());
  }

  @Test
  void polendEndorsementAuditMessage() {
    ParityHarness.assertParity(new PolendMessage());
  }

  @Test
  void undwrtDecisionMessage() {
    ParityHarness.assertParity(new UndwrtMessage());
  }

  /** POLNEW: row from {@link NewBusinessRules#newPolicy}; numbers via the parity formatter. */
  static final class PolnewMessage implements ParityAdapter {
    @Override
    public String program() {
      return "polnew-msg";
    }

    @Override
    public Map<String, String> run(ParityCase c) {
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
      Optional<String> rejection =
          NewBusinessRules.validate(
              request, c.date("as_of_date"), c.integer("holder_sqlcode") == 0);
      if (rejection.isPresent()) {
        return noMessage(rejection.get());
      }
      // Q-27 (policy-number format) is recorded on parity/programs/polnew; here the layout is
      // under test, so the number uses the COBOL formatter.
      String number = PolicyNumbers.fromSequence(c.integer("policy_seq"));
      PolicyEntity row =
          NewBusinessRules.newPolicy(request, number, LocalDateTime.of(2025, 1, 2, 3, 4));
      return message(LegacyMessageSource.POLNEW, row);
    }
  }

  /** POLRNW: row = fixture policy with the renewed values the polrnw golden records. */
  static final class PolrnwMessage implements ParityAdapter {
    private final Map<String, Map<String, String>> base = golden("polrnw");

    @Override
    public String program() {
      return "polrnw-msg";
    }

    @Override
    public Map<String, String> run(ParityCase c) {
      Map<String, String> g = base.get(c.caseId());
      String error = g.get("error_msg");
      if (!error.isEmpty()) {
        return noMessage(error);
      }
      PolicyEntity row = decoratedRow(c.caseId());
      row.setPolicyType(c.text("policy_type"));
      row.setPolicyStatus(g.get("policy_status"));
      row.setTotalPremium(new BigDecimal(g.get("policy_total_premium")));
      row.setEffectiveDate(date(g.get("policy_effective_date")));
      // Q-01 fix (AB-526, proposed): calendar arithmetic, so 20240229 renews to 20250228.
      row.setExpiryDate(
          strictDate(g.get("policy_expiry_date"))
              .orElseGet(() -> date(c.text("expiry_date")).plusYears(1)));
      row.setRenewalCount(Short.valueOf(g.get("renewal_count")));
      row.setUwStatus(g.get("uw_status"));
      row.setUpdatedBy("POLRNW");
      return message(LegacyMessageSource.POLRNW, row);
    }
  }

  /** POLEND: row = fixture policy with the post-endorsement premium from the polend golden. */
  static final class PolendMessage implements ParityAdapter {
    private final Map<String, Map<String, String>> base = golden("polend");

    @Override
    public String program() {
      return "polend-msg";
    }

    @Override
    public Map<String, String> run(ParityCase c) {
      Map<String, String> g = base.get(c.caseId());
      String error = g.get("error_msg");
      if (!error.isEmpty()) {
        return noMessage(error);
      }
      PolicyEntity row = decoratedRow(c.caseId());
      Optional<LocalDate> expiry = strictDate(c.text("expiry_date"));
      if (expiry.isEmpty()) {
        // A PostgreSQL DATE cannot hold 20250229 (Q-01), so Java has no such policy version.
        return noMessage(error);
      }
      row.setPolicyStatus(c.text("policy_status"));
      row.setEffectiveDate(date(c.text("effective_date")));
      row.setExpiryDate(expiry.get());
      row.setTotalPremium(new BigDecimal(g.get("total_premium")));
      row.setUpdatedBy("POLEND");
      return message(LegacyMessageSource.POLEND, row);
    }
  }

  /** UNDWRT: row = fixture policy with the decision and policy risk score from the golden. */
  static final class UndwrtMessage implements ParityAdapter {
    private final Map<String, Map<String, String>> base = golden("undwrt");

    @Override
    public String program() {
      return "undwrt-msg";
    }

    @Override
    public Map<String, String> run(ParityCase c) {
      Map<String, String> g = base.get(c.caseId());
      String error = g.get("error_msg");
      if (!error.isEmpty()) {
        return noMessage(error);
      }
      PolicyEntity row = decoratedRow(c.caseId());
      row.setPolicyType(c.text("policy_type"));
      row.setTotalPremium(c.decimal("total_premium"));
      row.setCoverageLimit(c.decimal("policy_limit"));
      row.setBranchCode(c.text("branch_code"));
      row.setRiskScore(Short.valueOf(g.get("risk_score")));
      row.setUwStatus(g.get("policy_uw_status"));
      row.setUpdatedBy("UNDWRT");
      return message(LegacyMessageSource.UNDWRT, row);
    }
  }

  /** A committed row with every field set, including ones the program does not populate. */
  static PolicyEntity decoratedRow(String caseId) {
    PolicyEntity p = new PolicyEntity();
    // The RECEIVE map stub moves the case id into POLICY-NUMBER PIC X(12).
    p.setPolicyNumber(
        caseId.length() > NUMBER_LENGTH ? caseId.substring(0, NUMBER_LENGTH) : caseId);
    p.setPolicyType("AUT");
    p.setPolicyStatus("AC");
    p.setEffectiveDate(LocalDate.of(2024, 3, 1));
    p.setExpiryDate(LocalDate.of(2025, 3, 1));
    p.setPolicyholderId("CUST999999");
    p.setAgentCode("AG9999");
    p.setBranchCode("B999");
    p.setTotalPremium(new BigDecimal("4321.00"));
    p.setDeductible(new BigDecimal("750.00"));
    p.setCoverageLimit(new BigDecimal("123456.00"));
    p.setInceptionDate(LocalDate.of(2020, 3, 1));
    p.setRenewalCount((short) 4);
    p.setUwStatus("AP");
    p.setRiskScore((short) 123);
    p.setWebIndicator("Y");
    p.setApiFlag("Y");
    p.setLastUpdated(LocalDateTime.of(2025, 1, 2, 3, 4));
    p.setUpdatedBy("SYSTEM");
    return p;
  }

  static Map<String, String> message(LegacyMessageSource source, PolicyEntity row) {
    PolicyRecord record = CopybookMapping.toRecord(row);
    Map<String, String> out = new LinkedHashMap<>();
    out.put("error_msg", "");
    out.put(
        "msg_hex",
        HEX.formatHex(LegacyPolicyMessages.encode(source, record, StandardCharsets.US_ASCII)));
    return out;
  }

  static Map<String, String> noMessage(String error) {
    Map<String, String> out = new LinkedHashMap<>();
    out.put("error_msg", error);
    out.put("msg_hex", "");
    return out;
  }

  /** Golden numerics drop leading zeros (Q-07: 99991231 + 10000 prints as 1231 = 00001231). */
  static LocalDate date(String yyyymmdd) {
    return LocalDate.parse("0".repeat(DATE_DIGITS - yyyymmdd.length()) + yyyymmdd, YYYYMMDD);
  }

  static Optional<LocalDate> strictDate(String yyyymmdd) {
    try {
      return Optional.of(date(yyyymmdd));
    } catch (java.time.format.DateTimeParseException e) {
      return Optional.empty();
    }
  }

  static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  /** Golden rows of a base program keyed by case id (columns by header name). */
  static Map<String, Map<String, String>> golden(String program) {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("parity/programs"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException("parity/programs not found");
    }
    try {
      List<String> lines =
          Files.readAllLines(dir.resolve("parity/programs/" + program + "/golden.csv"));
      String[] header = lines.get(0).split(",", -1);
      Map<String, Map<String, String>> rows = new HashMap<>();
      for (String line : lines.subList(1, lines.size())) {
        String[] cells = line.split(",", -1);
        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
          row.put(header[i], cells[i]);
        }
        rows.put(cells[0], row);
      }
      return rows;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
