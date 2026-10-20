package com.acme.insurance.pas.policy.events;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.jdbc.core.JdbcTemplate;

/** Backlog figures for the ADR's "outbox backlog age" alarm. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed singleton collaborators are shared by design")
public class OutboxMetrics {

  private final JdbcTemplate jdbc;

  public OutboxMetrics(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public double pending() {
    try {
      Long n =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM pas.outbox WHERE published_at IS NULL", Long.class);
      return n == null ? 0 : n;
    } catch (RuntimeException e) {
      return Double.NaN;
    }
  }

  public double oldestPendingAgeSeconds() {
    try {
      Double age =
          jdbc.queryForObject(
              "SELECT COALESCE(EXTRACT(EPOCH FROM (LOCALTIMESTAMP - MIN(created_at))), 0)"
                  + " FROM pas.outbox WHERE published_at IS NULL",
              Double.class);
      return age == null ? 0 : age;
    } catch (RuntimeException e) {
      return Double.NaN;
    }
  }
}
