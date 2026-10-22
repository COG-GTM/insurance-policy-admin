package com.acme.insurance.pas.policy.repository;

import com.acme.insurance.pas.domain.Endorsement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EndorsementRepository {
  private static final String SELECT =
      """
      SELECT policy_number, endorsement_seq, endorsement_type, effective_date, description,
             premium_adjustment, processed_date, processed_by
      FROM pas.endorsements
      """;

  private final JdbcClient jdbc;

  public EndorsementRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Endorsement endorsement) {
    jdbc.sql(
            """
            INSERT INTO pas.endorsements (
                policy_number, endorsement_seq, endorsement_type, effective_date, description,
                premium_adjustment, processed_date, processed_by
            ) VALUES (
                :policyNumber, :endorsementSeq, :endorsementType, :effectiveDate, :description,
                :premiumAdjustment, :processedDate, :processedBy
            )
            """)
        .param("policyNumber", endorsement.policyNumber())
        .param("endorsementSeq", endorsement.endorsementSeq())
        .param("endorsementType", endorsement.endorsementType())
        .param("effectiveDate", endorsement.effectiveDate())
        .param("description", endorsement.description())
        .param("premiumAdjustment", endorsement.premiumAdjustment())
        .param("processedDate", endorsement.processedDate())
        .param("processedBy", endorsement.processedBy())
        .update();
  }

  public Optional<Endorsement> findByKey(String policyNumber, int sequence) {
    return jdbc.sql(SELECT + " WHERE policy_number = :policyNumber AND endorsement_seq = :sequence")
        .param("policyNumber", policyNumber)
        .param("sequence", sequence)
        .query((row, rowNumber) -> map(row))
        .optional();
  }

  public List<Endorsement> findByPolicyNumber(String policyNumber) {
    return jdbc.sql(SELECT + " WHERE policy_number = :policyNumber ORDER BY endorsement_seq")
        .param("policyNumber", policyNumber)
        .query((row, rowNumber) -> map(row))
        .list();
  }

  private static Endorsement map(java.sql.ResultSet row) throws java.sql.SQLException {
    return new Endorsement(
        JdbcValues.charValue(row, "policy_number"),
        JdbcValues.integer(row, "endorsement_seq"),
        JdbcValues.charValue(row, "endorsement_type"),
        JdbcValues.date(row, "effective_date"),
        row.getString("description"),
        row.getBigDecimal("premium_adjustment"),
        JdbcValues.timestamp(row, "processed_date"),
        JdbcValues.charValue(row, "processed_by"));
  }

  /** POLEND 5000: MAX(ENDORSEMENT_SEQ); callers hold the policy row lock. */
  public Integer maxSequence(String policyNumber) {
    return jdbc.sql(
            "SELECT MAX(endorsement_seq) FROM pas.endorsements WHERE policy_number = :policyNumber")
        .param("policyNumber", policyNumber)
        .query(Integer.class)
        .optional()
        .orElse(null);
  }
}
