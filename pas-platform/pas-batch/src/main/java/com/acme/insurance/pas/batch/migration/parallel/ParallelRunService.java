package com.acme.insurance.pas.batch.migration.parallel;

import com.acme.insurance.pas.batch.extract.compare.ExtractCompare;
import com.acme.insurance.pas.batch.migration.MigrationProperties;
import com.acme.insurance.pas.batch.migration.source.Db2Source;
import com.acme.insurance.pas.batch.migration.source.MigrationTable;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "pas.migration.enabled", havingValue = "true")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are retained for the lifetime of this service.")
public final class ParallelRunService {
  private static final int PAGE_SIZE = 500;
  private static final List<String> COMPARED_COLUMNS =
      MigrationTable.PREMIUMS.columns().stream()
          .map(column -> column.toLowerCase(java.util.Locale.ROOT))
          .filter(
              column ->
                  !Set.of("policy_number", "coverage_seq", "term_effective_date").contains(column))
          .toList();
  private final JdbcTemplate jdbc;
  private final Db2Source source;
  private final MigrationProperties properties;
  private final ObjectMapper objectMapper;
  private final MeterRegistry metrics;
  private final TriageService triageService;

  public ParallelRunService(
      JdbcTemplate jdbc,
      Db2Source source,
      MigrationProperties properties,
      ObjectMapper objectMapper,
      MeterRegistry metrics,
      TriageService triageService) {
    this.jdbc = jdbc;
    this.source = source;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
    this.triageService = triageService;
  }

  public Result compare(LocalDate businessDate, UUID cycleId) {
    if (cycleId != null) {
      CycleDates dates =
          java.util.Objects.requireNonNull(
              jdbc.queryForObject(
                  "SELECT start_date, end_date FROM pas_batch.parallel_run_cycle WHERE cycle_id = ?",
                  (resultSet, rowNum) ->
                      new CycleDates(
                          resultSet.getDate(1).toLocalDate(), resultSet.getDate(2).toLocalDate()),
                  cycleId),
              "Parallel-run cycle was not found");
      if (businessDate.isBefore(dates.startDate()) || businessDate.isAfter(dates.endDate())) {
        throw new IllegalArgumentException("Business date is outside the parallel-run cycle");
      }
    }
    markAbsent(businessDate, "PREMIUMS");
    markAbsent(businessDate, "EXTRACT");
    List<ObservedDifference> differences = new ArrayList<>();
    Map<String, Map<String, Object>> legacy = readLegacyPremiums(businessDate);
    Map<String, Map<String, Object>> shadow = readShadowPremiums(businessDate);
    Set<String> keys = new LinkedHashSet<>(legacy.keySet());
    keys.addAll(shadow.keySet());
    for (String key : keys) {
      Map<String, Object> left = legacy.get(key);
      Map<String, Object> right = shadow.get(key);
      if (left == null || right == null) {
        differences.add(
            new ObservedDifference(
                "PREMIUMS",
                key,
                "__ROW__",
                left == null ? null : "PRESENT",
                right == null ? null : "PRESENT"));
        continue;
      }
      for (String column : COMPARED_COLUMNS) {
        Object a = left.get(column);
        Object b = right.get(column);
        if (!equivalent(a, b)) {
          differences.add(new ObservedDifference("PREMIUMS", key, column, value(a), value(b)));
        }
      }
    }
    differences.addAll(compareExtracts(businessDate));
    for (ObservedDifference difference : differences) {
      persistDifference(businessDate, cycleId, difference);
    }
    Map<String, Object> counts =
        Map.of(
            "premiums",
                differences.stream()
                    .filter(difference -> difference.artifact().equals("PREMIUMS"))
                    .count(),
            "extracts",
                differences.stream()
                    .filter(difference -> difference.artifact().equals("EXTRACT"))
                    .count());
    if (cycleId != null) {
      jdbc.update(
          """
          INSERT INTO pas_batch.parallel_run_date (cycle_id, business_date, last_run_at, counts)
          VALUES (?, ?, now(), ?::jsonb)
          ON CONFLICT (cycle_id, business_date) DO UPDATE
          SET last_run_at = EXCLUDED.last_run_at, counts = EXCLUDED.counts
          """,
          cycleId,
          businessDate,
          writeJson(counts));
      updateCycleStatus(cycleId);
    }
    triageService.updateParallelRunGauges();
    metrics
        .counter("pas.parallel_run.differences", "artifact", "PREMIUMS")
        .increment(counts.get("premiums") instanceof Number n ? n.doubleValue() : 0);
    metrics
        .counter("pas.parallel_run.differences", "artifact", "EXTRACT")
        .increment(counts.get("extracts") instanceof Number n ? n.doubleValue() : 0);
    return new Result(businessDate, cycleId, differences.size(), counts);
  }

  private Map<String, Map<String, Object>> readLegacyPremiums(LocalDate businessDate) {
    Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
    List<Object> cursor = List.of();
    while (true) {
      List<Map<String, Object>> page = source.readPremiumsForDate(businessDate, cursor, PAGE_SIZE);
      if (page.isEmpty()) {
        return rows;
      }
      for (Map<String, Object> row : page) {
        rows.put(premiumKey(row), row);
      }
      Map<String, Object> last = page.getLast();
      cursor =
          MigrationTable.PREMIUMS.keyColumns().stream()
              .map(key -> last.get(key.toLowerCase(java.util.Locale.ROOT)))
              .toList();
    }
  }

  private Map<String, Map<String, Object>> readShadowPremiums(LocalDate businessDate) {
    List<Map<String, Object>> rows =
        jdbc.query(
            """
            SELECT policy_number, coverage_seq, term_effective_date, term_expiry_date, base_rate,
                   territory_factor, class_factor, experience_mod, schedule_mod, discount_pct,
                   surcharge_amt, tax_amt, total_premium, installment_code, installment_amt,
                   calc_date, calc_by
            FROM pas_shadow.premiums
            WHERE business_date = ?
            ORDER BY policy_number, coverage_seq, term_effective_date
            """,
            (resultSet, rowNum) -> {
              Map<String, Object> row = new LinkedHashMap<>();
              var metadata = resultSet.getMetaData();
              for (int index = 1; index <= metadata.getColumnCount(); index++) {
                Object value = resultSet.getObject(index);
                if (value instanceof java.sql.Date date) {
                  value = date.toLocalDate();
                }
                row.put(metadata.getColumnLabel(index).toLowerCase(java.util.Locale.ROOT), value);
              }
              return row;
            },
            businessDate);
    Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
    rows.forEach(row -> indexed.put(premiumKey(row), row));
    return indexed;
  }

  private List<ObservedDifference> compareExtracts(LocalDate businessDate) {
    Path legacyDate = Path.of(properties.parallelRun().legacyExtractDir(), businessDate.toString());
    List<ObservedDifference> differences = new ArrayList<>();
    for (String job : List.of("dailyExtractJob", "monthlyExposureJob")) {
      Path expected = legacyDate.resolve(job);
      if (!Files.isDirectory(expected)) {
        expected = legacyDate;
      }
      Path actual = latestExtractDir(job, businessDate);
      List<String> lines;
      if (!Files.isDirectory(expected)) {
        lines = List.of("MISSING legacy extract output for " + job);
      } else if (!Files.isDirectory(actual)) {
        lines = List.of("MISSING shadow extract output for " + job);
      } else {
        lines = ExtractCompare.differences(expected, actual);
      }
      for (String line : lines) {
        String digest = sha256(line);
        differences.add(
            new ObservedDifference("EXTRACT", digest, "EXTRACT_LINE", "DIFFERENT", "DIFFERENT"));
      }
    }
    return differences;
  }

  private Path latestExtractDir(String job, LocalDate businessDate) {
    Path dateDir = Path.of(properties.parallelRun().extractWorkDir(), job, businessDate.toString());
    if (!Files.isDirectory(dateDir)) {
      return dateDir.resolve("missing");
    }
    try (var paths = Files.list(dateDir)) {
      return paths
          .filter(Files::isDirectory)
          .max(
              java.util.Comparator.comparingLong(
                  path -> Long.parseLong(path.getFileName().toString())))
          .orElse(dateDir.resolve("missing"));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to list shadow extract output", exception);
    }
  }

  private void markAbsent(LocalDate businessDate, String artifact) {
    jdbc.update(
        """
        UPDATE pas_batch.parallel_run_difference
        SET present = false, last_seen_run = now()
        WHERE business_date = ? AND artifact = ?
        """,
        businessDate,
        artifact);
  }

  private void persistDifference(
      LocalDate businessDate, UUID cycleId, ObservedDifference difference) {
    jdbc.update(
        """
        INSERT INTO pas_batch.parallel_run_difference
            (cycle_id, business_date, artifact, record_key, field, legacy_value, shadow_value,
             present, first_seen_run, last_seen_run)
        VALUES (?, ?, ?, ?, ?, ?, ?, true, now(), now())
        ON CONFLICT (business_date, artifact, record_key, field) DO UPDATE
        SET cycle_id = COALESCE(EXCLUDED.cycle_id, pas_batch.parallel_run_difference.cycle_id),
            legacy_value = EXCLUDED.legacy_value,
            shadow_value = EXCLUDED.shadow_value,
            present = true,
            last_seen_run = now(),
            classification = CASE
                WHEN pas_batch.parallel_run_difference.legacy_value IS DISTINCT FROM EXCLUDED.legacy_value
                  OR pas_batch.parallel_run_difference.shadow_value IS DISTINCT FROM EXCLUDED.shadow_value
                THEN 'UNTRIAGED'
                ELSE pas_batch.parallel_run_difference.classification
            END,
            quirk_id = CASE
                WHEN pas_batch.parallel_run_difference.legacy_value IS DISTINCT FROM EXCLUDED.legacy_value
                  OR pas_batch.parallel_run_difference.shadow_value IS DISTINCT FROM EXCLUDED.shadow_value
                THEN NULL
                ELSE pas_batch.parallel_run_difference.quirk_id
            END,
            triage_note = CASE
                WHEN pas_batch.parallel_run_difference.legacy_value IS DISTINCT FROM EXCLUDED.legacy_value
                  OR pas_batch.parallel_run_difference.shadow_value IS DISTINCT FROM EXCLUDED.shadow_value
                THEN NULL
                ELSE pas_batch.parallel_run_difference.triage_note
            END,
            triaged_by = CASE
                WHEN pas_batch.parallel_run_difference.legacy_value IS DISTINCT FROM EXCLUDED.legacy_value
                  OR pas_batch.parallel_run_difference.shadow_value IS DISTINCT FROM EXCLUDED.shadow_value
                THEN NULL
                ELSE pas_batch.parallel_run_difference.triaged_by
            END,
            triaged_at = CASE
                WHEN pas_batch.parallel_run_difference.legacy_value IS DISTINCT FROM EXCLUDED.legacy_value
                  OR pas_batch.parallel_run_difference.shadow_value IS DISTINCT FROM EXCLUDED.shadow_value
                THEN NULL
                ELSE pas_batch.parallel_run_difference.triaged_at
            END
        """,
        cycleId,
        businessDate,
        difference.artifact(),
        difference.recordKey(),
        difference.field(),
        difference.legacyValue(),
        difference.shadowValue());
  }

  private void updateCycleStatus(UUID cycleId) {
    CycleDates cycle =
        java.util.Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT start_date, end_date FROM pas_batch.parallel_run_cycle WHERE cycle_id = ?",
                (resultSet, rowNum) ->
                    new CycleDates(
                        resultSet.getDate(1).toLocalDate(), resultSet.getDate(2).toLocalDate()),
                cycleId),
            "Parallel-run cycle was not found");
    long expected =
        java.time.temporal.ChronoUnit.DAYS.between(cycle.startDate(), cycle.endDate()) + 1;
    Long runDates =
        jdbc.queryForObject(
            "SELECT count(*) FROM pas_batch.parallel_run_date WHERE cycle_id = ?",
            Long.class,
            cycleId);
    Long unresolved =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM pas_batch.parallel_run_difference
            WHERE cycle_id = ? AND present AND classification IN ('UNTRIAGED', 'BUG')
            """,
            Long.class,
            cycleId);
    String status =
        cycleStatus(
            expected,
            java.util.Objects.requireNonNullElse(runDates, 0L),
            java.util.Objects.requireNonNullElse(unresolved, 0L));
    jdbc.update(
        "UPDATE pas_batch.parallel_run_cycle SET status = ? WHERE cycle_id = ?", status, cycleId);
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to serialize parallel-run counts", exception);
    }
  }

  static String cycleStatus(long expectedDates, long completedDates, long unresolvedDifferences) {
    return completedDates == expectedDates && unresolvedDifferences == 0
        ? "CLEAN"
        : unresolvedDifferences > 0 ? "DIFFERENCES" : "RUNNING";
  }

  private static String premiumKey(Map<String, Object> row) {
    return row.get("policy_number").toString().stripTrailing()
        + "|"
        + row.get("coverage_seq")
        + "|"
        + row.get("term_effective_date");
  }

  static boolean equivalent(Object left, Object right) {
    if (left instanceof BigDecimal a && right instanceof BigDecimal b) {
      return a.compareTo(b) == 0;
    }
    if (left instanceof String a && right instanceof String b) {
      return a.stripTrailing().equals(b.stripTrailing());
    }
    return Objects.equals(left, right);
  }

  private static String value(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.stripTrailingZeros().toPlainString();
    }
    return value.toString().stripTrailing();
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record Result(
      LocalDate businessDate, UUID cycleId, int differenceCount, Map<String, Object> counts) {
    public Result {
      counts = Map.copyOf(counts);
    }
  }

  private record ObservedDifference(
      String artifact, String recordKey, String field, String legacyValue, String shadowValue) {}

  private record CycleDates(LocalDate startDate, LocalDate endDate) {}
}
