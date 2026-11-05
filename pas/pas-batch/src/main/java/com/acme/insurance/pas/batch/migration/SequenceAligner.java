package com.acme.insurance.pas.batch.migration;

import com.acme.insurance.pas.persistence.DatabaseVendor;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Keeps ACMEINS.POLICY_SEQ on the receiving side above every number the sending side has issued, so
 * a policy number is never handed out twice: after the bulk load (PostgreSQL above DB2) and before
 * DB2 writes are re-enabled by a rollback (DB2 above PostgreSQL, ADR 0001 rollback step). The
 * sequence only ever moves forward.
 */
public final class SequenceAligner {

  /** High-water marks before and after an alignment. */
  public record Alignment(long sendingHighWater, long receivingBefore, long receivingNext) {}

  private SequenceAligner() {}

  public static long highWater(JdbcClient jdbc, DatabaseVendor vendor) {
    Long value =
        switch (vendor) {
          case POSTGRESQL ->
              jdbc.sql("SELECT last_value FROM acmeins.policy_seq").query(Long.class).single();
          case DB2 ->
              jdbc.sql(
                      "SELECT COALESCE(MAXASSIGNEDVAL, START - 1) FROM SYSIBM.SYSSEQUENCES"
                          + " WHERE SEQSCHEMA = 'ACMEINS' AND SEQNAME = 'POLICY_SEQ'")
                  .query(Long.class)
                  .single();
        };
    return value;
  }

  public static Alignment align(
      JdbcClient sending,
      DatabaseVendor sendingVendor,
      JdbcClient receiving,
      DatabaseVendor receivingVendor,
      long gap) {
    long sendingHighWater = highWater(sending, sendingVendor);
    long receivingBefore = highWater(receiving, receivingVendor);
    long next = sendingHighWater + gap;
    if (receivingBefore >= next) {
      return new Alignment(sendingHighWater, receivingBefore, receivingBefore + 1);
    }
    switch (receivingVendor) {
      case POSTGRESQL ->
          receiving
              .sql("SELECT setval('acmeins.policy_seq', :next, false)")
              .param("next", next)
              .query(Long.class)
              .single();
      case DB2 ->
          // DDL takes no parameter markers; next is a long computed above, never user input.
          receiving.sql("ALTER SEQUENCE ACMEINS.POLICY_SEQ RESTART WITH " + next).update();
    }
    return new Alignment(sendingHighWater, receivingBefore, next);
  }
}
