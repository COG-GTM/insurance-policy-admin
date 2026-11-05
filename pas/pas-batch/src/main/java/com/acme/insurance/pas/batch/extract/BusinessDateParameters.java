package com.acme.insurance.pas.batch.extract;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

/**
 * The {@code businessDate} job parameter (ISO date) replaces the legacy {@code CURRENT DATE}. When
 * the scheduler does not pass one, today in the business time zone is used. The validator also
 * checks the SFTP endpoint of the consumer, so a misconfigured job stops before any PII is staged.
 */
public class BusinessDateParameters implements JobParametersIncrementer, JobParametersValidator {

  public static final String BUSINESS_DATE = "businessDate";

  private final Clock clock;
  private final ZoneId zone;
  private final Supplier<List<String>> configurationProblems;

  public BusinessDateParameters(
      Clock clock, ZoneId zone, Supplier<List<String>> configurationProblems) {
    this.clock = clock;
    this.zone = zone;
    this.configurationProblems = configurationProblems;
  }

  public static LocalDate businessDate(JobParameters parameters) {
    return LocalDate.parse(parameters.getString(BUSINESS_DATE));
  }

  @Override
  public JobParameters getNext(JobParameters parameters) {
    JobParametersBuilder next =
        new JobParametersBuilder(parameters == null ? new JobParameters() : parameters);
    if (parameters == null || parameters.getString(BUSINESS_DATE) == null) {
      next.addString(BUSINESS_DATE, LocalDate.now(clock.withZone(zone)).toString());
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
    List<String> problems = configurationProblems.get();
    if (!problems.isEmpty()) {
      throw new JobParametersInvalidException("extract delivery is not configured: " + problems);
    }
  }
}
