package com.acme.insurance.pas.policy.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Business date and audit timestamps. "Today" (CICS {@code FORMATTIME YYYYMMDD}) is taken in the
 * configured business time zone ({@code pas.business-time-zone}); audit timestamps are UTC, which
 * is how the inquiry API already renders {@code lastUpdated}.
 */
@Component
public class BusinessCalendar {

  private final Clock clock;
  private final ZoneId businessZone;

  public BusinessCalendar(Clock clock, @Value("${pas.business-time-zone}") ZoneId businessZone) {
    this.clock = clock;
    this.businessZone = businessZone;
  }

  public LocalDate today() {
    return LocalDate.now(clock.withZone(businessZone));
  }

  public LocalDateTime nowUtc() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  public Instant now() {
    return clock.instant();
  }

  public ZoneId businessZone() {
    return businessZone;
  }
}
