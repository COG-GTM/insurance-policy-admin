package com.acme.insurance.pas.batch.migration.report;

import com.acme.insurance.pas.batch.migration.triage.Classification;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Prometheus gauges for the latest check of each type: {@code pas_migration_unexplained}, {@code
 * pas_migration_differences{classification}}, {@code pas_migration_check_passed} and {@code
 * pas_migration_last_run_seconds}. Alert when unexplained &gt; 0 or the last run is older than a
 * day.
 */
public final class MigrationMetrics {

  private final Map<CheckType, AtomicReference<CheckReport>> latest =
      new EnumMap<>(CheckType.class);

  public MigrationMetrics(MeterRegistry registry) {
    for (CheckType type : CheckType.values()) {
      AtomicReference<CheckReport> ref = new AtomicReference<>();
      latest.put(type, ref);
      String check = type.name();
      Gauge.builder("pas.migration.unexplained", ref, r -> value(r, x -> x.unexplained()))
          .tag("check", check)
          .description("BUG + UNCLASSIFIED differences in the latest check")
          .register(registry);
      Gauge.builder("pas.migration.check.passed", ref, r -> value(r, x -> x.passed() ? 1 : 0))
          .tag("check", check)
          .register(registry);
      Gauge.builder(
              "pas.migration.last.run", ref, r -> value(r, x -> x.finishedAt().getEpochSecond()))
          .tag("check", check)
          .baseUnit("seconds")
          .register(registry);
      for (Classification c : Classification.values()) {
        Gauge.builder("pas.migration.differences", ref, r -> value(r, x -> x.count(c)))
            .tag("check", check)
            .tag("classification", c.name())
            .register(registry);
      }
    }
  }

  public void update(CheckReport report) {
    latest.get(report.checkType()).set(report);
  }

  private static double value(
      AtomicReference<CheckReport> ref, java.util.function.ToDoubleFunction<CheckReport> f) {
    CheckReport report = ref.get();
    return report == null ? Double.NaN : f.applyAsDouble(report);
  }
}
