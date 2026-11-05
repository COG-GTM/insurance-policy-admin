package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.batch.extract.BusinessDateParameters;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

/**
 * Identifying parameters of a premium run: {@code businessDate} (YYYY-MM-DD, default today in the
 * configured zone) and {@code rerun}. A failed run is restarted with its own parameters (Spring
 * Boot reuses them), so it resumes from the last committed chunk. A new run of a business date that
 * already completed gets the next {@code rerun} number, a new job instance that recalculates the
 * whole book and replaces the day's PREMIUMS rows and report (Q-26).
 */
public class PremiumJobParameters implements JobParametersIncrementer, JobParametersValidator {

  public static final String BUSINESS_DATE = BusinessDateParameters.BUSINESS_DATE;
  public static final String RERUN = "rerun";

  private final Clock clock;
  private final ZoneId zone;
  private final Supplier<String> configurationProblem;

  public PremiumJobParameters(Clock clock, ZoneId zone, Supplier<String> configurationProblem) {
    this.clock = clock;
    this.zone = zone;
    this.configurationProblem = configurationProblem;
  }

  public static LocalDate businessDate(JobParameters parameters) {
    return BusinessDateParameters.businessDate(parameters);
  }

  @Override
  public JobParameters getNext(JobParameters previous) {
    String today = LocalDate.now(clock.withZone(zone)).toString();
    JobParametersBuilder next = new JobParametersBuilder().addString(BUSINESS_DATE, today);
    if (previous != null && today.equals(previous.getString(BUSINESS_DATE))) {
      Long rerun = previous.getLong(RERUN);
      next.addLong(RERUN, rerun == null ? 1L : rerun + 1);
    }
    return next.toJobParameters();
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
