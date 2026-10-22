package com.acme.insurance.pas.policy.inquiry;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.CoverageStatus;
import com.acme.insurance.pas.domain.CoverageType;
import com.acme.insurance.pas.domain.CustomerType;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyHolder;
import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.domain.PolicyType;
import com.acme.insurance.pas.domain.RiskTier;
import com.acme.insurance.pas.domain.UnderwritingStatus;
import com.acme.insurance.pas.domain.YesNo;
import com.acme.insurance.pas.pii.PiiCipher;
import com.acme.insurance.pas.pii.PiiContext;
import com.acme.insurance.pas.pii.PolicyHolderPii;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.support.AbstractSqlTypeValue;
import org.springframework.stereotype.Repository;

@Repository
public class PolicyInquiryRepository {
  private static final String ENTITLEMENT_PREDICATE =
      """
      (:wholeBook OR
       (CAST(:agentId AS text) IS NOT NULL AND rtrim(p.agent_code) = CAST(:agentId AS text)) OR
       (cardinality(CAST(:branchCodes AS text[])) > 0
        AND rtrim(p.branch_code) = ANY(CAST(:branchCodes AS text[]))))
      """;

  private final JdbcClient jdbc;
  private final PiiCipher cipher;

  public PolicyInquiryRepository(JdbcClient jdbc, PiiCipher cipher) {
    this.jdbc = jdbc;
    this.cipher = cipher;
  }

  public Optional<Policy> findPolicy(String policyNumber, CallerEntitlements entitlements) {
    return jdbc.sql(
            """
            SELECT p.policy_number, p.policy_type, p.policy_status, p.effective_date, p.expiry_date,
                   p.policyholder_id, p.agent_code, p.branch_code, p.total_premium, p.deductible,
                   p.coverage_limit, p.inception_date, p.renewal_count, p.uw_status, p.risk_score,
                   p.web_indicator, p.api_flag, p.last_updated, p.updated_by
            FROM pas.policies p
            WHERE p.policy_number = :policyNumber AND
            """
                + ENTITLEMENT_PREDICATE)
        .param("policyNumber", policyNumber)
        .params(entitlementParameters(entitlements))
        .query((row, rowNumber) -> policy(row))
        .optional();
  }

  public List<Coverage> findCoverages(String policyNumber, CallerEntitlements entitlements) {
    return jdbc.sql(
            """
            SELECT c.policy_number, c.sequence_num, c.coverage_type, c.description,
                   c.coverage_limit, c.deductible, c.premium, c.effective_date, c.expiry_date,
                   c.status, c.coinsurance_pct, c.rating_territory, c.class_code
            FROM pas.coverages c
            JOIN pas.policies p ON p.policy_number = c.policy_number
            WHERE p.policy_number = :policyNumber AND
            """
                + ENTITLEMENT_PREDICATE
                + " ORDER BY c.sequence_num")
        .param("policyNumber", policyNumber)
        .params(entitlementParameters(entitlements))
        .query((row, rowNumber) -> coverage(row))
        .list();
  }

  public Optional<PolicyHolder> findHolder(String policyNumber, CallerEntitlements entitlements) {
    return jdbc.sql(
            """
            SELECT h.cust_id, h.cust_type, h.last_name, h.first_name, h.middle_init,
                   h.company_name, h.addr_line1, h.addr_line2, h.city, h.state_code, h.zip_code,
                   h.country_code, h.phone, h.email, h.date_of_birth_enc, h.ssn_last4_enc,
                   h.tax_id_enc, h.credit_score_enc, h.risk_tier, h.gdpr_consent, h.created_date,
                   h.last_updated
            FROM pas.policies p
            JOIN pas.policy_holders h ON h.cust_id = p.policyholder_id
            WHERE p.policy_number = :policyNumber AND
            """
                + ENTITLEMENT_PREDICATE)
        .param("policyNumber", policyNumber)
        .params(entitlementParameters(entitlements))
        .query((row, rowNumber) -> holder(row))
        .optional();
  }

  private static java.util.Map<String, Object> entitlementParameters(
      CallerEntitlements entitlements) {
    return java.util.Map.of(
        "wholeBook", entitlements.wholeBook(),
        "agentId", entitlements.agentId() == null ? "" : entitlements.agentId(),
        "branchCodes", textArray(entitlements.branchCodes()));
  }

  private static AbstractSqlTypeValue textArray(List<String> branchCodes) {
    Object[] values = branchCodes.toArray(String[]::new);
    return new AbstractSqlTypeValue() {
      @Override
      protected Object createTypeValue(Connection connection, int sqlType, String typeName)
          throws SQLException {
        return connection.createArrayOf("text", values);
      }
    };
  }

  private Policy policy(ResultSet row) throws SQLException {
    return new Policy(
        charValue(row, "policy_number"),
        coded(row, "policy_type", PolicyType.class),
        coded(row, "policy_status", PolicyStatus.class),
        date(row, "effective_date"),
        date(row, "expiry_date"),
        charValue(row, "policyholder_id"),
        charValue(row, "agent_code"),
        charValue(row, "branch_code"),
        row.getBigDecimal("total_premium"),
        row.getBigDecimal("deductible"),
        row.getBigDecimal("coverage_limit"),
        date(row, "inception_date"),
        integer(row, "renewal_count"),
        coded(row, "uw_status", UnderwritingStatus.class),
        integer(row, "risk_score"),
        coded(row, "web_indicator", YesNo.class),
        coded(row, "api_flag", YesNo.class),
        timestamp(row, "last_updated"),
        charValue(row, "updated_by"));
  }

  private static Coverage coverage(ResultSet row) throws SQLException {
    return new Coverage(
        charValue(row, "policy_number"),
        integer(row, "sequence_num"),
        coded(row, "coverage_type", CoverageType.class),
        row.getString("description"),
        row.getBigDecimal("coverage_limit"),
        row.getBigDecimal("deductible"),
        row.getBigDecimal("premium"),
        date(row, "effective_date"),
        date(row, "expiry_date"),
        coded(row, "status", CoverageStatus.class),
        integer(row, "coinsurance_pct"),
        charValue(row, "rating_territory"),
        charValue(row, "class_code"));
  }

  private PolicyHolder holder(ResultSet row) throws SQLException {
    String custId = charValue(row, "cust_id");
    return new PolicyHolder(
        custId,
        coded(row, "cust_type", CustomerType.class),
        row.getString("last_name"),
        row.getString("first_name"),
        charValue(row, "middle_init"),
        row.getString("company_name"),
        row.getString("addr_line1"),
        row.getString("addr_line2"),
        row.getString("city"),
        charValue(row, "state_code"),
        charValue(row, "zip_code"),
        charValue(row, "country_code"),
        row.getString("phone"),
        row.getString("email"),
        PolicyHolderPii.decryptDate(
            row.getBytes("date_of_birth_enc"), cipher, context(custId, "date_of_birth_enc")),
        PolicyHolderPii.decryptString(
            row.getBytes("ssn_last4_enc"), cipher, context(custId, "ssn_last4_enc")),
        PolicyHolderPii.decryptString(
            row.getBytes("tax_id_enc"), cipher, context(custId, "tax_id_enc")),
        PolicyHolderPii.decryptInteger(
            row.getBytes("credit_score_enc"), cipher, context(custId, "credit_score_enc")),
        coded(row, "risk_tier", RiskTier.class),
        coded(row, "gdpr_consent", YesNo.class),
        date(row, "created_date"),
        timestamp(row, "last_updated"));
  }

  private static PiiContext context(String custId, String column) {
    return new PiiContext("policy_holders", column, custId);
  }

  private static String charValue(ResultSet row, String column) throws SQLException {
    String value = row.getString(column);
    return value == null ? null : value.replaceFirst(" +$", "");
  }

  private static LocalDate date(ResultSet row, String column) throws SQLException {
    return row.getObject(column, LocalDate.class);
  }

  private static OffsetDateTime timestamp(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class);
  }

  private static Integer integer(ResultSet row, String column) throws SQLException {
    Number value = (Number) row.getObject(column);
    return value == null ? null : value.intValue();
  }

  private static <E extends Enum<E> & com.acme.insurance.pas.domain.CobolCode> Coded<E> coded(
      ResultSet row, String column, Class<E> type) throws SQLException {
    String value = charValue(row, column);
    return value == null ? null : Coded.of(type, value);
  }
}
