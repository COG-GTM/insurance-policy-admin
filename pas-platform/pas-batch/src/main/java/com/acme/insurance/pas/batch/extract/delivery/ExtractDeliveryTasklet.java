package com.acme.insurance.pas.batch.extract.delivery;

import com.acme.insurance.pas.batch.extract.layout.ExtractLayouts;
import com.acme.insurance.pas.common.routing.RouteState;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

public final class ExtractDeliveryTasklet {
  private static final Logger log = LoggerFactory.getLogger(ExtractDeliveryTasklet.class);
  private final ExtractDeliveryService deliveryService;
  private final String workDir;

  public ExtractDeliveryTasklet(ExtractDeliveryService deliveryService, String workDir) {
    this.deliveryService = deliveryService;
    this.workDir = workDir;
  }

  public Tasklet forTarget(String target, RouteState mode) {
    return (StepContribution contribution, ChunkContext context) -> {
      JobExecution execution = context.getStepContext().getStepExecution().getJobExecution();
      String jobName = execution.getJobInstance().getJobName();
      if (mode == RouteState.RETIRED) {
        throw new IllegalStateException("Extract capability is retired");
      }
      if (mode == RouteState.LEGACY || mode == RouteState.SHADOW) {
        log.atInfo()
            .addKeyValue("event", "pas.batch.extract.delivery.skipped")
            .addKeyValue("job", jobName)
            .addKeyValue("jobExecutionId", execution.getId())
            .log("Extract delivery skipped by legacy route");
        return RepeatStatus.FINISHED;
      }
      LocalDate businessDate =
          LocalDate.parse(execution.getJobParameters().getString("businessDate"));
      String directory =
          Path.of(workDir, jobName, businessDate.toString(), execution.getId().toString())
              .toString();
      List<ExtractDeliveryService.DeliveryFile> files;
      if (jobName.equals("dailyExtractJob")) {
        files =
            List.of(
                new ExtractDeliveryService.DeliveryFile(
                    Path.of(directory, "policy_extract.dat"),
                    "policy_extract.dat",
                    ExtractLayouts.DAILY_POLICY.versionString(),
                    stepWrites(execution, "policyExtractStep")),
                new ExtractDeliveryService.DeliveryFile(
                    Path.of(directory, "coverage_extract.dat"),
                    "coverage_extract.dat",
                    ExtractLayouts.DAILY_COVERAGE.versionString(),
                    stepWrites(execution, "coverageExtractStep")));
      } else {
        String suffix = businessDate.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
        files =
            List.of(
                new ExtractDeliveryService.DeliveryFile(
                    Path.of(directory, "exposure_monthly.D" + suffix + ".csv"),
                    "exposure_monthly.csv",
                    ExtractLayouts.MONTHLY_EXPOSURE.versionString(),
                    stepWrites(execution, "exposureExtractStep")));
      }
      deliveryService.deliver(target, jobName, execution.getId(), businessDate, files);
      return RepeatStatus.FINISHED;
    };
  }

  private static long stepWrites(JobExecution execution, String stepName) {
    return execution.getStepExecutions().stream()
        .filter(step -> step.getStepName().equals(stepName))
        .mapToLong(org.springframework.batch.core.StepExecution::getWriteCount)
        .findFirst()
        .orElse(0);
  }
}
