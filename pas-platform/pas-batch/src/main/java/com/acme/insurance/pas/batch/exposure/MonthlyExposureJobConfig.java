package com.acme.insurance.pas.batch.exposure;

import com.acme.insurance.pas.batch.alert.JobFailureAlertListener;
import com.acme.insurance.pas.batch.extract.ExtractOutputs;
import com.acme.insurance.pas.batch.extract.SingleRunPerDateTasklet;
import com.acme.insurance.pas.batch.extract.SqlExtractTasklet;
import com.acme.insurance.pas.batch.transfer.DeliveryFile;
import com.acme.insurance.pas.batch.transfer.DeliveryStepTasklet;
import com.acme.insurance.pas.batch.transfer.SecureFileTransfer;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
 * {@code monthlyExposureJob}: replaces {@code jcl/MONTHLY-EXPOSURE.jcl} (CA-7 PAS0200). STEP010
 * exposure aggregation, STEP020 delivery to Actuarial over SFTP (was clear-text FTP).
 */
@Configuration
public class MonthlyExposureJobConfig {

  public static final String JOB_NAME = "monthlyExposureJob";
  public static final String EXPOSURE_FILE = "exposure_monthly.csv";
  public static final String DESTINATION = "actuarial";

  /**
   * Q-33: the JCL overwrote {@code exposure_monthly.csv} every month; deliver one dated file per
   * run instead ({@code exposure_monthly_YYYYMMDD.csv}, run date = business date).
   */
  public static String remoteFileName(LocalDate businessDate) {
    return "exposure_monthly_" + businessDate.format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";
  }

  @Bean
  public Step exposureExtractStep(
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
            EXPOSURE_FILE,
            ExposureQueries.MONTHLY_EXPOSURE,
            ExposureCsv.LRECL,
            List.of(ExposureCsv.header()),
            ExposureCsv::row);
    return new StepBuilder("exposureExtractStep", jobRepository)
        .tasklet(tasklet, transactionManager)
        .build();
  }

  @Bean
  public Step actuarialDeliveryStep(
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
                        outputs.location(JOB_NAME, businessDate, EXPOSURE_FILE),
                        remoteFileName(businessDate))));
    return new StepBuilder("actuarialDeliveryStep", jobRepository)
        .tasklet(tasklet, transactionManager)
        .build();
  }

  @Bean
  public Job monthlyExposureJob(
      JobRepository jobRepository,
      Step exposureExtractStep,
      Step actuarialDeliveryStep,
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
        .next(exposureExtractStep)
        .next(actuarialDeliveryStep)
        .build();
  }
}
