package com.acme.insurance.pas.policyservice.config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * The business date of the online transactions: CICS ASKTIME/FORMATTIME gave the region's local
 * date. The zone is {@code pas.business-zone} (FU-525-6 confirms the production value).
 */
public final class BusinessClock {

  private final Clock clock;

  public BusinessClock(Clock clock, ZoneId zone) {
    this.clock = Objects.requireNonNull(clock, "clock").withZone(zone);
  }

  public LocalDate today() {
    return LocalDate.now(clock);
  }

  public Clock clock() {
    return clock;
  }
}
