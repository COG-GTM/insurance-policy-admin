package com.acme.insurance.pas.batch.migration.report;

import com.acme.insurance.pas.batch.migration.triage.Classification;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

/**
 * Prometheus gauges for the latest check of each type: {@code pas_migration_unexplained}, {@code
 * pas_migration_differences{classification}}, {@code pas_migration_check_passed} and {@code
 * pas_migration_last_run_seconds}. Seeded from {@code pas.migration_check_runs} at startup so a
 * restart does not clear a failing check. Alert when unexplained &gt; 0 or the last run is older
 * than a day.
 */
public final class MigrationMetrics {

  /** Numbers behind the gauges for one check type. */
  record Snapshot(
      int unexplained, boolean passed, long finishedEpoch, Map<Classification, Integer> counts) {
    Snapshot {
      counts = Map.copyOf(counts);
    }
  }

  private final Map<CheckType, AtomicReference<Snapshot>> latest = new EnumMap<>(CheckType.class);

  public MigrationMetrics(MeterRegistry registry) {
    for (CheckType type : CheckType.values()) {
      AtomicReference<Snapshot> ref = new AtomicReference<>();
      latest.put(type, ref);
      String check = type.name();
      Gauge.builder("pas.migration.unexplained", ref, r -> value(r, Snapshot::unexplained))
          .tag("check", check)
          .description("BUG + UNCLASSIFIED differences in the latest check")
          .register(registry);
      Gauge.builder("pas.migration.check.passed", ref, r -> value(r, s -> s.passed() ? 1 : 0))
          .tag("check", check)
          .register(registry);
      Gauge.builder("pas.migration.last.run", ref, r -> value(r, Snapshot::finishedEpoch))
          .tag("check", check)
          .baseUnit("seconds")
          .register(registry);
      for (Classification c : Classification.values()) {
        Gauge.builder(
                "pas.migration.differences", ref, r -> value(r, s -> s.counts().getOrDefault(c, 0)))
            .tag("check", check)
            .tag("classification", c.name())
            .register(registry);
      }
    }
  }

  public void update(CheckReport report) {
    latest
        .get(report.checkType())
        .set(
            new Snapshot(
                report.unexplained(),
                report.passed(),
                report.finishedAt().getEpochSecond(),
                report.classificationCounts()));
  }

  /** Restores the gauges from the latest persisted run of each check type. */
  public void seed(CheckRunStore store) {
    for (CheckType type : CheckType.values()) {
      store
          .latest(type)
          .ifPresent(
              row -> {
                Map<Classification, Integer> counts = new EnumMap<>(Classification.class);
                counts.put(Classification.BUG, number(row.get("bug_count")));
                counts.put(Classification.APPROVED_FIX, number(row.get("approved_fix_count")));
                counts.put(Classification.DATA_ISSUE, number(row.get("data_issue_count")));
                counts.put(Classification.UNCLASSIFIED, number(row.get("unclassified_count")));
                long finished =
                    ((Timestamp) row.get("finished_at"))
                        .toLocalDateTime()
                        .toEpochSecond(ZoneOffset.UTC);
                latest
                    .get(type)
                    .compareAndSet(
                        null,
                        new Snapshot(
                            number(row.get("unexplained_count")),
                            "PASS".equals(row.get("status")),
                            finished,
                            counts));
              });
    }
  }

  private static int number(Object value) {
    return value == null ? 0 : ((Number) value).intValue();
  }

  private static double value(AtomicReference<Snapshot> ref, ToDoubleFunction<Snapshot> f) {
    Snapshot snapshot = ref.get();
    return snapshot == null ? Double.NaN : f.applyAsDouble(snapshot);
  }
}
