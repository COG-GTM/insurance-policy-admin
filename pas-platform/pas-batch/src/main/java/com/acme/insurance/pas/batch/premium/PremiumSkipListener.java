package com.acme.insurance.pas.batch.premium;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.jdbc.core.JdbcTemplate;

public class PremiumSkipListener implements SkipListener<PremiumPolicy, PremiumRow> {
  private static final Logger log = LoggerFactory.getLogger(PremiumSkipListener.class);
  private final JdbcTemplate jdbcTemplate;
  private final long jobInstanceId;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The Spring-managed JDBC template is retained for skipped-item records.")
  public PremiumSkipListener(JdbcTemplate jdbcTemplate, long jobInstanceId) {
    this.jdbcTemplate = jdbcTemplate;
    this.jobInstanceId = jobInstanceId;
  }

  @Override
  public void onSkipInRead(Throwable throwable) {}

  @Override
  public void onSkipInWrite(PremiumRow item, Throwable throwable) {
    record(item.policyNumber(), throwable);
  }

  @Override
  public void onSkipInProcess(PremiumPolicy item, Throwable throwable) {
    record(item.policyNumber(), throwable);
  }

  private void record(String policyNumber, Throwable throwable) {
    String errorType = throwable.getClass().getSimpleName();
    jdbcTemplate.update(
        "INSERT INTO pas_batch.premium_run_item "
            + "(job_instance_id, policy_number, status, total_premium, error_type) "
            + "VALUES (?, ?, 'ERROR', NULL, ?) "
            + "ON CONFLICT (job_instance_id, policy_number) DO UPDATE SET "
            + "status = 'ERROR', total_premium = NULL, error_type = EXCLUDED.error_type",
        jobInstanceId,
        policyNumber,
        errorType);
    log.atWarn()
        .addKeyValue("event", "pas.batch.premium.policy.skipped")
        .addKeyValue("policyNumber", policyNumber)
        .addKeyValue("errorType", errorType)
        .log("Premium policy skipped");
  }
}
