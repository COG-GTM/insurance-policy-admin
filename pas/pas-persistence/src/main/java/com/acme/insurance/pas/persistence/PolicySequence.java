package com.acme.insurance.pas.persistence;

import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ACMEINS.POLICY_SEQ, the source of new policy numbers on both databases (POLNEW {@code
 * 4000-GENERATE-POLICY-NUM}). Sequence syntax is not portable, so the statement follows the vendor.
 */
public final class PolicySequence {

  private final JdbcClient jdbc;
  private final String sql;

  public PolicySequence(JdbcClient jdbc, DatabaseVendor vendor) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.sql =
        switch (Objects.requireNonNull(vendor, "vendor")) {
          case POSTGRESQL -> "SELECT nextval('acmeins.policy_seq')";
          case DB2 -> "SELECT NEXT VALUE FOR ACMEINS.POLICY_SEQ FROM SYSIBM.SYSDUMMY1";
        };
  }

  public long next() {
    return jdbc.sql(sql).query(Long.class).single();
  }
}
