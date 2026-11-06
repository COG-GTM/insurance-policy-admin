package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.batch.extract.BusinessDateParameters;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * <p>As an incrementer it always names a new instance: today's date, with the next {@code rerun}
 * number when today already has a run. That instance re-rates the whole book and replaces the day's
 * PREMIUMS rows and report (Q-26). Spring Batch's next-instance operation relies on this.
 *
 * <p>Restarting a failed run is the scheduler's decision, not the incrementer's: {@link
 * PremiumScheduledLauncher} asks {@link #restartableRunOfToday()} first, and if today's latest
 * execution is {@code FAILED} or {@code STOPPED} it relaunches that instance with its own
 * parameters, so it resumes from the last committed chunk of every partition.
 */
public class PremiumJobParameters implements JobParametersIncrementer, JobParametersValidator {

  public static final String BUSINESS_DATE = BusinessDateParameters.BUSINESS_DATE;
  public static final String RERUN = "rerun";

  private final Clock clock;
  private final ZoneId zone;
  private final Supplier<String> configurationProblem;
  private final Function<String, List<JobExecution>> runsOfBusinessDate;

  /**
   * @param runsOfBusinessDate the last execution of every job instance of a business date
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
    String today = today();
    List<JobParameters> todays = new ArrayList<>();
    runsOfBusinessDate.apply(today).forEach(run -> todays.add(run.getJobParameters()));
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

  /**
   * The identifying parameters of today's latest execution (highest execution id, whichever
   * instance it belongs to) if it is {@code FAILED} or {@code STOPPED}. A {@code STARTED} or {@code
   * UNKNOWN} execution (killed pod) cannot be restarted by Spring Batch and yields nothing.
   */
  public Optional<JobParameters> restartableRunOfToday() {
    return runsOfBusinessDate.apply(today()).stream()
        .max(Comparator.comparing(JobExecution::getId))
        .filter(run -> isRestartable(run.getStatus()))
        .map(run -> new JobParameters(run.getJobParameters().getIdentifyingParameters()));
  }

  private String today() {
    return LocalDate.now(clock.withZone(zone)).toString();
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
