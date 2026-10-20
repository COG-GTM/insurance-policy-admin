package com.acme.insurance.pas.batch.extract;

import static com.acme.insurance.pas.batch.extract.ExtractOutputs.RECORD_CHARSET;
import static com.acme.insurance.pas.batch.extract.ExtractOutputs.date;
import static com.acme.insurance.pas.batch.extract.ExtractOutputs.text;

import com.acme.insurance.pas.batch.alert.JobFailureAlertListener;
import com.acme.insurance.pas.batch.transfer.DeliveryFile;
import com.acme.insurance.pas.batch.transfer.DeliveryStepTasklet;
import com.acme.insurance.pas.batch.transfer.SecureFileTransfer;
import com.acme.insurance.pas.common.copybook.RecordValues;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code dailyExtractJob}: replaces {@code jcl/DAILY-EXTRACT.jcl} (CA-7 PAS0100). STEP010 policy
 * extract, STEP020 coverage extract, STEP030 delivery to Claims over SFTP (was clear-text FTP).
 */
@Configuration
public class DailyExtractJobConfig {

  public static final String JOB_NAME = "dailyExtractJob";
  public static final String POLICY_FILE = "policy_extract.dat";
  public static final String COVERAGE_FILE = "coverage_extract.dat";
  public static final String DESTINATION = "claims";

  @Bean
  public Step policyExtractStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      NamedParameterJdbcTemplate extractJdbcTemplate,
      ExtractOutputs outputs,
      MeterRegistry meters) {
    SqlExtractTasklet tasklet =
        new SqlExtractTasklet(
            extractJdbcTemplate,
            outputs,
            meters,
            JOB_NAME,
            POLICY_FILE,
            ExtractQueries.POLICY_EXTRACT,
            ExtractRecordLayouts.POLICY_LRECL,
            List.of(),
            rs ->
                ExtractRecordLayouts.POLICY_EXTRACT.encode(
                    new RecordValues()
                        .put("POLICY_NUMBER", text(rs, "policy_number"))
                        .put("POLICY_TYPE", text(rs, "policy_type"))
                        .put("POLICY_STATUS", text(rs, "policy_status"))
                        .put("EFFECTIVE_DATE", date(rs, "effective_date"))
                        .put("EXPIRY_DATE", date(rs, "expiry_date"))
                        .put("POLICYHOLDER_ID", text(rs, "policyholder_id"))
                        .put("AGENT_CODE", text(rs, "agent_code"))
                        .put("BRANCH_CODE", text(rs, "branch_code"))
                        .put("TOTAL_PREMIUM", rs.getBigDecimal("total_premium"))
                        .put("DEDUCTIBLE", rs.getBigDecimal("deductible"))
                        .put("COVERAGE_LIMIT", rs.getBigDecimal("coverage_limit"))
                        .put("UW_STATUS", text(rs, "uw_status"))
                        .put("RISK_SCORE", rs.getBigDecimal("risk_score")),
                    RECORD_CHARSET));
    return new StepBuilder("policyExtractStep", jobRepository)
        .tasklet(tasklet, transactionManager)
        .build();
  }

  @Bean
  public Step coverageExtractStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      NamedParameterJdbcTemplate extractJdbcTemplate,
      ExtractOutputs outputs,
      MeterRegistry meters) {
    SqlExtractTasklet tasklet =
        new SqlExtractTasklet(
            extractJdbcTemplate,
            outputs,
            meters,
            JOB_NAME,
            COVERAGE_FILE,
            ExtractQueries.COVERAGE_EXTRACT,
            ExtractRecordLayouts.COVERAGE_LRECL,
            List.of(),
            rs ->
                ExtractRecordLayouts.COVERAGE_EXTRACT.encode(
                    new RecordValues()
                        .put("POLICY_NUMBER", text(rs, "policy_number"))
                        .put("SEQUENCE_NUM", rs.getBigDecimal("sequence_num"))
                        .put("COVERAGE_TYPE", text(rs, "coverage_type"))
                        .put("DESCRIPTION", text(rs, "description"))
                        .put("COVERAGE_LIMIT", rs.getBigDecimal("coverage_limit"))
                        .put("DEDUCTIBLE", rs.getBigDecimal("deductible"))
                        .put("PREMIUM", rs.getBigDecimal("premium"))
                        .put("EFFECTIVE_DATE", date(rs, "effective_date"))
                        .put("EXPIRY_DATE", date(rs, "expiry_date"))
                        .put("STATUS", text(rs, "status")),
                    RECORD_CHARSET));
    return new StepBuilder("coverageExtractStep", jobRepository)
        .tasklet(tasklet, transactionManager)
        .build();
  }

  @Bean
  public Step claimsDeliveryStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      SecureFileTransfer transfer,
      ExtractOutputs outputs) {
    DeliveryStepTasklet tasklet =
        new DeliveryStepTasklet(
            transfer,
            DESTINATION,
            businessDate ->
                List.of(
                    new DeliveryFile(
                        outputs.location(JOB_NAME, businessDate, POLICY_FILE), POLICY_FILE),
                    new DeliveryFile(
                        outputs.location(JOB_NAME, businessDate, COVERAGE_FILE), COVERAGE_FILE)));
    return new StepBuilder("claimsDeliveryStep", jobRepository)
        .tasklet(tasklet, transactionManager)
        .build();
  }

  @Bean
  public Job dailyExtractJob(
      JobRepository jobRepository,
      Step policyExtractStep,
      Step coverageExtractStep,
      Step claimsDeliveryStep,
      JobFailureAlertListener alertListener,
      JobExplorer jobExplorer,
      PlatformTransactionManager transactionManager) {
    Step guard =
        new StepBuilder(JOB_NAME + "SingleRunGuard", jobRepository)
            .tasklet(new SingleRunPerDateTasklet(jobExplorer), transactionManager)
            .build();
    return new JobBuilder(JOB_NAME, jobRepository)
        .listener(alertListener)
        .start(guard)
        .next(policyExtractStep)
        .next(coverageExtractStep)
        .next(claimsDeliveryStep)
        .build();
  }
}
