package com.acme.insurance.pas.persistence;

import java.util.Locale;

/**
 * The two databases pas-persistence runs on: DB2 for z/OS while it is the system of record, and
 * PostgreSQL after cutover (ADR 0001 D3). Only statements without a portable form switch on it.
 */
public enum DatabaseVendor {
  POSTGRESQL,
  DB2;

  /** From JDBC {@code DatabaseMetaData.getDatabaseProductName()}. */
  public static DatabaseVendor fromProductName(String productName) {
    String name = productName == null ? "" : productName.toUpperCase(Locale.ROOT);
    if (name.contains("POSTGRES")) {
      return POSTGRESQL;
    }
    if (name.startsWith("DB2")) {
      return DB2;
    }
    throw new IllegalArgumentException("unsupported database: " + productName);
  }
}
