package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.batch.extract.BusinessDateParameters;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

/**
 * Identifying parameters of a premium run: {@code businessDate} (YYYY-MM-DD, default today in the
 * configured zone) and {@code rerun}.
 *
 * <p>A launch without arguments (the scheduler, and its pod retry) asks the incrementer for the
 * parameters. If the latest run of today is {@code FAILED} or {@code STOPPED}, the incrementer
 * returns that run's own parameters, so the launcher restarts the same instance from the last
 * committed chunk of every partition. Otherwise (no run yet: the first run of the day; or the
 * latest run completed, was abandoned, or is left {@code STARTED}/{@code UNKNOWN} by a killed pod)
 * it returns today's date with the next {@code rerun} number when today already has a run: a new
 * instance that re-rates the whole book and replaces the day's PREMIUMS rows and report (Q-26).
 */
public class PremiumJobParameters implements JobParametersIncrementer, JobParametersValidator {

  public static final String BUSINESS_DATE = BusinessDateParameters.BUSINESS_DATE;
  public static final String RERUN = "rerun";

  private final Clock clock;
  private final ZoneId zone;
  private final Supplier<String> configurationProblem;
  private final Function<String, List<JobExecution>> runsOfBusinessDate;

  /**
   * @param runsOfBusinessDate the last execution of every job instance of a business date, newest
   *     instance first
   */
  public PremiumJobParameters(
      Clock clock,
      ZoneId zone,
      Supplier<String> configurationProblem,
      Function<String, List<JobExecution>> runsOfBusinessDate) {
    this.clock = clock;
    this.zone = zone;
    this.configurationProblem = configurationProblem;
    this.runsOfBusinessDate = runsOfBusinessDate;
  }

  public static LocalDate businessDate(JobParameters parameters) {
    return BusinessDateParameters.businessDate(parameters);
  }

  @Override
  public JobParameters getNext(JobParameters previous) {
    String today = LocalDate.now(clock.withZone(zone)).toString();
    List<JobExecution> runs = runsOfBusinessDate.apply(today);
    if (!runs.isEmpty() && isRestartable(runs.get(0).getStatus())) {
      return new JobParameters(runs.get(0).getJobParameters().getIdentifyingParameters());
    }
    List<JobParameters> todays = new ArrayList<>();
    runs.forEach(run -> todays.add(run.getJobParameters()));
    if (previous != null && today.equals(previous.getString(BUSINESS_DATE))) {
      todays.add(previous);
    }
    JobParametersBuilder next = new JobParametersBuilder().addString(BUSINESS_DATE, today);
    todays.stream()
        .mapToLong(p -> p.getLong(RERUN) == null ? 0L : p.getLong(RERUN))
        .max()
        .ifPresent(last -> next.addLong(RERUN, last + 1));
    return next.toJobParameters();
  }

  private static boolean isRestartable(BatchStatus status) {
    return status == BatchStatus.FAILED || status == BatchStatus.STOPPED;
  }

  @Override
  public void validate(JobParameters parameters) throws JobParametersInvalidException {
    String value = parameters == null ? null : parameters.getString(BUSINESS_DATE);
    if (value == null) {
      throw new JobParametersInvalidException(BUSINESS_DATE + " (YYYY-MM-DD) is required");
    }
    try {
      LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw new JobParametersInvalidException(BUSINESS_DATE + " must be YYYY-MM-DD");
    }
    String problem = configurationProblem.get();
    if (problem != null) {
      throw new JobParametersInvalidException(
          "premium recalculation is not configured: " + Objects.toString(problem));
    }
  }
}
