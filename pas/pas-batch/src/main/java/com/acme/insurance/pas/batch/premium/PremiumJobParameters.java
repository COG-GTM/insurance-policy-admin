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
 * <p>The incrementer always returns a new instance: today's date, with the next {@code rerun}
 * number when today already has a run. It re-rates the whole book and replaces the day's PREMIUMS
 * rows and report (Q-26). {@link #restartOfToday()} is what the scheduler launch ({@link
 * PremiumScheduledLauncher}) restarts instead: today's latest execution when it is {@code FAILED}
 * or {@code STOPPED}.
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
   * instance it belongs to) when it is {@code FAILED} or {@code STOPPED}. A pod killed mid-run
   * leaves its execution {@code STARTED}, which is not restartable.
   */
  public Optional<JobParameters> restartOfToday() {
    return runsOfBusinessDate.apply(today()).stream()
        .max(Comparator.comparing(JobExecution::getId))
        .filter(latest -> isRestartable(latest.getStatus()))
        .map(latest -> new JobParameters(latest.getJobParameters().getIdentifyingParameters()));
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
