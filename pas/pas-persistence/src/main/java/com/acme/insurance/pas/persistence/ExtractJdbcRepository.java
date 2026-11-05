package com.acme.insurance.pas.persistence;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Feed queries for the Claims daily extract and the Actuarial monthly exposure (AB-527), and the
 * delivery receipts in {@code pasapp.extract_delivery}.
 *
 * <p>The SQL keeps the selection of jcl/DAILY-EXTRACT.jcl and jcl/MONTHLY-EXPOSURE.jcl column for
 * column. The only rewrite is {@code CURRENT DATE - 1 DAY}: it becomes a bound timestamp computed
 * from the business date, so the statements run unchanged on DB2 and PostgreSQL and a re-run of a
 * business date selects the same rows (quirk Q-527-1).
 */
public class ExtractJdbcRepository {

  public static final String DAILY_POLICY_SQL =
      """
      SELECT POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS,
             EFFECTIVE_DATE, EXPIRY_DATE,
             POLICYHOLDER_ID, AGENT_CODE, BRANCH_CODE,
             TOTAL_PREMIUM, DEDUCTIBLE, COVERAGE_LIMIT,
             UW_STATUS, RISK_SCORE
      FROM ACMEINS.POLICIES
      WHERE POLICY_STATUS IN ('AC', 'PN')
        AND LAST_UPDATED >= :windowStart
      ORDER BY POLICY_NUMBER
      """;

  public static final String ACTIVE_COVERAGE_SQL =
      """
      SELECT POLICY_NUMBER, SEQUENCE_NUM, COVERAGE_TYPE,
             DESCRIPTION, COVERAGE_LIMIT, DEDUCTIBLE,
             PREMIUM, EFFECTIVE_DATE, EXPIRY_DATE, STATUS
      FROM ACMEINS.COVERAGES
      WHERE STATUS = 'AC'
      ORDER BY POLICY_NUMBER, SEQUENCE_NUM
      """;

  /**
   * The legacy statement orders by POLICY_TYPE, BRANCH_CODE only (quirk Q-34). Ordering by every
   * group column is one of the orders the legacy statement allows, and makes the file stable.
   */
  public static final String MONTHLY_EXPOSURE_SQL =
      """
      SELECT P.POLICY_TYPE,
             P.BRANCH_CODE,
             C.COVERAGE_TYPE,
             C.RATING_TERRITORY,
             C.CLASS_CODE,
             COUNT(*) AS POLICY_COUNT,
             SUM(C.COVERAGE_LIMIT) AS TOTAL_EXPOSURE,
             SUM(C.PREMIUM) AS TOTAL_PREMIUM,
             SUM(C.DEDUCTIBLE) AS TOTAL_DEDUCTIBLE
      FROM ACMEINS.POLICIES P
      JOIN ACMEINS.COVERAGES C
        ON P.POLICY_NUMBER = C.POLICY_NUMBER
      WHERE P.POLICY_STATUS = 'AC'
        AND C.STATUS = 'AC'
      GROUP BY P.POLICY_TYPE, P.BRANCH_CODE,
               C.COVERAGE_TYPE, C.RATING_TERRITORY,
               C.CLASS_CODE
      ORDER BY P.POLICY_TYPE, P.BRANCH_CODE,
               C.COVERAGE_TYPE, C.RATING_TERRITORY,
               C.CLASS_CODE
      """;

  private final JdbcClient jdbc;

  public ExtractJdbcRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Legacy {@code LAST_UPDATED >= CURRENT DATE - 1 DAY}: DB2 compares the timestamp with midnight
   * of the day before the run date.
   */
  public static Timestamp dailyWindowStart(LocalDate businessDate) {
    return Timestamp.valueOf(businessDate.minusDays(1).atStartOfDay());
  }

  public void streamDailyPolicies(LocalDate businessDate, Consumer<ExtractRow> sink) {
    jdbc.sql(DAILY_POLICY_SQL)
        .param("windowStart", dailyWindowStart(businessDate))
        .query(rows(sink));
  }

  public void streamActiveCoverages(Consumer<ExtractRow> sink) {
    jdbc.sql(ACTIVE_COVERAGE_SQL).query(rows(sink));
  }

  public void streamMonthlyExposure(Consumer<ExtractRow> sink) {
    jdbc.sql(MONTHLY_EXPOSURE_SQL).query(rows(sink));
  }

  public void recordDelivery(DeliveryReceipt r) {
    jdbc.sql(
            """
            INSERT INTO PASAPP.EXTRACT_DELIVERY
              (FEED, BUSINESS_DATE, FILE_NAME, RECORD_COUNT, BYTE_COUNT, SHA256, DESTINATION,
               STATUS, ATTEMPTS, JOB_EXECUTION_ID, DETAIL, CONFIRMED_AT)
            VALUES
              (:feed, :businessDate, :fileName, :recordCount, :byteCount, :sha256, :destination,
               :status, :attempts, :jobExecutionId, :detail, :confirmedAt)
            """)
        .param("feed", r.feed())
        .param("businessDate", r.businessDate())
        .param("fileName", r.fileName())
        .param("recordCount", r.recordCount())
        .param("byteCount", r.byteCount())
        .param("sha256", r.sha256())
        .param("destination", r.destination())
        .param("status", r.status().name())
        .param("attempts", r.attempts())
        .param("jobExecutionId", r.jobExecutionId())
        .param("detail", truncate(r.detail(), 512))
        .param("confirmedAt", r.confirmedAt())
        .update();
  }

  public List<DeliveryReceipt> findDeliveries(String feed, LocalDate businessDate) {
    return jdbc.sql(
            """
            SELECT FEED, BUSINESS_DATE, FILE_NAME, RECORD_COUNT, BYTE_COUNT, SHA256, DESTINATION,
                   STATUS, ATTEMPTS, JOB_EXECUTION_ID, DETAIL, CONFIRMED_AT
            FROM PASAPP.EXTRACT_DELIVERY
            WHERE FEED = :feed AND BUSINESS_DATE = :businessDate
            ORDER BY DELIVERY_ID
            """)
        .param("feed", feed)
        .param("businessDate", businessDate)
        .query(
            (rs, n) ->
                new DeliveryReceipt(
                    Columns.text(rs, "FEED"),
                    Columns.date(rs, "BUSINESS_DATE"),
                    Columns.text(rs, "FILE_NAME"),
                    rs.getLong("RECORD_COUNT"),
                    rs.getLong("BYTE_COUNT"),
                    Columns.text(rs, "SHA256"),
                    Columns.text(rs, "DESTINATION"),
                    DeliveryReceipt.Status.valueOf(Columns.text(rs, "STATUS")),
                    rs.getInt("ATTEMPTS"),
                    (Long) rs.getObject("JOB_EXECUTION_ID", Long.class),
                    Columns.text(rs, "DETAIL"),
                    Columns.timestamp(rs, "CONFIRMED_AT")))
        .list();
  }

  private static RowCallbackHandler rows(Consumer<ExtractRow> sink) {
    return rs -> sink.accept(ExtractRow.of(rs));
  }

  private static String truncate(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }
}
