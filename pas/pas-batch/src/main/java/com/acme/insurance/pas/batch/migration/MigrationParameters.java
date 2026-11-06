package com.acme.insurance.pas.batch.migration;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;

/**
 * Every launch of a migration job is a new instance ({@code run.id}) with {@code businessDate}
 * defaulting to today in the business zone. Sync and reconciliation are idempotent, so a failed run
 * is repaired by simply launching again; there is no partial state to restart from.
 */
public class MigrationParameters implements JobParametersIncrementer {

  public static final String BUSINESS_DATE = "businessDate";
  public static final String RUN_ID = "run.id";

  private final Clock clock;
  private final ZoneId zone;

  public MigrationParameters(Clock clock, ZoneId zone) {
    this.clock = clock;
    this.zone = zone;
  }

  @Override
  public JobParameters getNext(JobParameters previous) {
    Long last = previous == null ? null : previous.getLong(RUN_ID);
    long run = last == null ? 0 : last;
    return new JobParametersBuilder()
        .addLong(RUN_ID, run + 1)
        .addString(BUSINESS_DATE, LocalDate.now(clock.withZone(zone)).toString())
        .toJobParameters();
  }

  public static LocalDate businessDate(JobParameters parameters) {
    String value = parameters.getString(BUSINESS_DATE);
    return value == null ? LocalDate.now() : LocalDate.parse(value);
  }
}
