package com.acme.insurance.pas.persistence;

import static com.acme.insurance.pas.persistence.Columns.text;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PASAPP.DEFAULT_COVERAGES (V52): the table that replaces POLNEW's hardcoded coverage list. */
public class DefaultCoverageJdbcRepository {

  private final JdbcClient jdbc;

  public DefaultCoverageJdbcRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Default coverages of {@code policyType} in sequence order; empty for LIF, HLT and others. */
  public List<DefaultCoverage> forPolicyType(String policyType) {
    return jdbc.sql(
            """
            SELECT policy_type, coverage_seq, coverage_type, description
              FROM pasapp.default_coverages WHERE policy_type = ? ORDER BY coverage_seq
            """)
        .param(policyType)
        .query(
            (rs, row) ->
                new DefaultCoverage(
                    text(rs, "policy_type"),
                    rs.getInt("coverage_seq"),
                    text(rs, "coverage_type"),
                    text(rs, "description")))
        .list();
  }
}
