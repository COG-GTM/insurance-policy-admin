package com.acme.insurance.pas.policy.inquiry;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.CoverageStatus;
import com.acme.insurance.pas.domain.CoverageType;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyHolder;
import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.domain.PolicyType;
import com.acme.insurance.pas.domain.UnderwritingStatus;
import com.acme.insurance.pas.domain.YesNo;
import com.acme.insurance.pas.policy.repository.PolicyHolderRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.support.AbstractSqlTypeValue;
import org.springframework.stereotype.Repository;

@Repository
public class PolicyInquiryRepository {
  private static final String ENTITLEMENT_PREDICATE =
      """
      (:wholeBook OR
       (CAST(:agentId AS bpchar) IS NOT NULL AND p.agent_code = CAST(:agentId AS bpchar)) OR
       (cardinality(CAST(:branchCodes AS bpchar[])) > 0
        AND p.branch_code = ANY(CAST(:branchCodes AS bpchar[]))))
      """;

  private final JdbcClient jdbc;
  private final PolicyHolderRepository policyHolderRepository;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring-managed repository collaborator is retained for holder lookup.")
  public PolicyInquiryRepository(JdbcClient jdbc, PolicyHolderRepository policyHolderRepository) {
    this.jdbc = jdbc;
    this.policyHolderRepository = policyHolderRepository;
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
    Optional<String> policyholderId =
        jdbc.sql(
                """
            SELECT p.policyholder_id
            FROM pas.policies p
            WHERE p.policy_number = :policyNumber AND
            """
                    + ENTITLEMENT_PREDICATE)
            .param("policyNumber", policyNumber)
            .params(entitlementParameters(entitlements))
            .query((row, rowNumber) -> trailingTrimmedChar(row, "policyholder_id"))
            .optional();
    return policyholderId.flatMap(policyHolderRepository::findByCustId);
  }

  public static String entitlementPredicate(String policyAlias) {
    return ENTITLEMENT_PREDICATE.replace("p.", policyAlias + ".");
  }

  public static java.util.Map<String, Object> entitlementParameters(
      CallerEntitlements entitlements) {
    return java.util.Map.of(
        "wholeBook", entitlements.wholeBook(),
        "agentId", new SqlParameterValue(Types.VARCHAR, entitlements.agentId()),
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
        trimmedString(row, "policy_number"),
        coded(row, "policy_type", PolicyType.class),
        coded(row, "policy_status", PolicyStatus.class),
        date(row, "effective_date"),
        date(row, "expiry_date"),
        trimmedString(row, "policyholder_id"),
        trimmedString(row, "agent_code"),
        trimmedString(row, "branch_code"),
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
        trimmedString(row, "updated_by"));
  }

  private static Coverage coverage(ResultSet row) throws SQLException {
    return new Coverage(
        trimmedString(row, "policy_number"),
        integer(row, "sequence_num"),
        coded(row, "coverage_type", CoverageType.class),
        trimmedString(row, "description"),
        row.getBigDecimal("coverage_limit"),
        row.getBigDecimal("deductible"),
        row.getBigDecimal("premium"),
        date(row, "effective_date"),
        date(row, "expiry_date"),
        coded(row, "status", CoverageStatus.class),
        integer(row, "coinsurance_pct"),
        trimmedString(row, "rating_territory"),
        trimmedString(row, "class_code"));
  }

  private static String trimmedString(ResultSet row, String column) throws SQLException {
    String value = row.getString(column);
    return value == null ? null : value.trim();
  }

  private static String trailingTrimmedChar(ResultSet row, String column) throws SQLException {
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
    String value = trimmedString(row, column);
    return value == null ? null : Coded.of(type, value);
  }
}
