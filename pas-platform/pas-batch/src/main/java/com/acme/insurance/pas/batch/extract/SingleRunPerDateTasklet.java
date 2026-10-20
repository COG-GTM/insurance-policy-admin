package com.acme.insurance.pas.batch.extract;

import java.util.Objects;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * First step of each extract job: fails when an earlier execution of the same job is still running
 * for the same business date, so overlapping reruns cannot replace or deliver each other's files.
 * Execution ids come from the job repository's sequence, so when two same-date runs start together
 * the lower id proceeds and only the later one fails.
 */
public class SingleRunPerDateTasklet implements Tasklet {

  private final JobExplorer jobExplorer;

  public SingleRunPerDateTasklet(JobExplorer jobExplorer) {
    this.jobExplorer = jobExplorer;
  }

  @Override
  public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
    JobExecution current = chunkContext.getStepContext().getStepExecution().getJobExecution();
    String jobName = current.getJobInstance().getJobName();
    String businessDate = businessDate(current);
    for (JobExecution other : jobExplorer.findRunningJobExecutions(jobName)) {
      if (other.getId() != null
          && other.getId() < current.getId()
          && Objects.equals(businessDate(other), businessDate)) {
        throw new IllegalStateException(
            jobName
                + " is already running for businessDate="
                + businessDate
                + " (execution "
                + other.getId()
                + "); wait for it to finish before rerunning");
      }
    }
    return RepeatStatus.FINISHED;
  }

  private static String businessDate(JobExecution execution) {
    JobParameter<?> parameter =
        execution.getJobParameters().getParameter(ExtractOutputs.BUSINESS_DATE);
    return parameter == null ? null : String.valueOf(parameter.getValue());
  }
}
