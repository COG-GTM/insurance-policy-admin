package com.acme.insurance.pas.policy.newbusiness;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ShadowNewBusinessRepository {
  private final JdbcClient jdbc;

  public ShadowNewBusinessRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(
      String requestHash, String policyType, List<String> coverageTypes, List<String> errorFields) {
    jdbc.sql(
            """
            INSERT INTO pas_shadow.new_business_results (
                request_hash, policy_type, coverage_types, outcome, error_fields
            ) VALUES (
                :requestHash, :policyType, CAST(:coverageTypes AS text[]), :outcome,
                CAST(:errorFields AS text[])
            )
            """)
        .param("requestHash", requestHash)
        .param("policyType", policyType)
        .param("coverageTypes", coverageTypes.toArray(String[]::new))
        .param("outcome", errorFields.isEmpty() ? "VALID" : "INVALID")
        .param("errorFields", errorFields.toArray(String[]::new))
        .update();
  }
}
