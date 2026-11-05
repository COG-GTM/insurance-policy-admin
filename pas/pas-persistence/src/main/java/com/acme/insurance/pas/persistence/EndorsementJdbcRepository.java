package com.acme.insurance.pas.persistence;

import static com.acme.insurance.pas.persistence.Columns.date;
import static com.acme.insurance.pas.persistence.Columns.decimal;
import static com.acme.insurance.pas.persistence.Columns.text;
import static com.acme.insurance.pas.persistence.Columns.timestamp;

import com.acme.insurance.pas.domain.Endorsement;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ACMEINS.ENDORSEMENTS (AB-526). The next sequence is {@code MAX + 1} as in POLEND, which is only
 * safe while the caller holds the policy row lock ({@link
 * PolicyJdbcRepository#findPolicyForUpdate}); the primary key turns a missed lock into a failed
 * insert, never a duplicate.
 */
public class EndorsementJdbcRepository {

  private final JdbcClient jdbc;

  public EndorsementJdbcRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int nextSequence(String policyNumber) {
    return jdbc.sql(
            "SELECT COALESCE(MAX(endorsement_seq), 0) + 1 FROM acmeins.endorsements"
                + " WHERE policy_number = ?")
        .param(policyNumber)
        .query(Integer.class)
        .single();
  }

  public void insert(Endorsement e) {
    new Insert("acmeins.endorsements")
        .set("policy_number", e.policyNumber())
        .set("endorsement_seq", e.endorsementSeq())
        .set("endorsement_type", e.endorsementTypeCode())
        .set("effective_date", e.effectiveDate())
        .set("description", e.description())
        .set("premium_adjustment", e.premiumAdjustment())
        .setOrDefault("processed_date", e.processedDate())
        .set("processed_by", e.processedBy())
        .execute(jdbc);
  }

  public List<Endorsement> findByPolicy(String policyNumber) {
    return jdbc.sql(
            "SELECT * FROM acmeins.endorsements WHERE policy_number = ? ORDER BY endorsement_seq")
        .param(policyNumber)
        .query(
            (rs, row) ->
                new Endorsement(
                    text(rs, "policy_number"),
                    rs.getInt("endorsement_seq"),
                    text(rs, "endorsement_type"),
                    date(rs, "effective_date"),
                    text(rs, "description"),
                    decimal(rs, "premium_adjustment"),
                    timestamp(rs, "processed_date"),
                    text(rs, "processed_by")))
        .list();
  }
}
