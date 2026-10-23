package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.migration.parallel.ParallelRunService;
import com.acme.insurance.pas.batch.migration.parallel.TriageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParallelRunCycleIT extends MigrationIntegrationTestSupport {
  @Test
  void overlappingCyclesKeepIndependentDifferencesAndClassifications() throws Exception {
    seedSource(1);
    LocalDate date = LocalDate.of(2025, 2, 1);
    sourceJdbc.update(
        "UPDATE acmeins.premiums SET calc_date = ? WHERE policy_number = ? AND coverage_seq = 2",
        date,
        "POL000000001");
    Path root = Files.createTempDirectory("pas-parallel-overlap-it");
    Path legacy = root.resolve("legacy");
    Path extracts = root.resolve("extracts");
    Files.createDirectories(legacy.resolve(date.toString()));
    for (String job : new String[] {"dailyExtractJob", "monthlyExposureJob"}) {
      Files.createDirectories(extracts.resolve(job).resolve(date.toString()).resolve("1"));
    }
    ParallelRunService service =
        new ParallelRunService(
            target,
            source,
            withExtractPaths(legacy, extracts),
            new ObjectMapper(),
            metrics,
            new TriageService(target, metrics));
    UUID cycleA = createOneDateCycle(date, "overlap A");
    UUID cycleB = createOneDateCycle(date, "overlap B");
    copyPremiumsToShadow(date, 21L);
    target.update(
        "UPDATE pas_shadow.premiums SET total_premium = total_premium + 10 "
            + "WHERE business_date = ? AND policy_number = 'POL000000001' AND coverage_seq = 2",
        date);

    service.compare(date, cycleA);
    service.compare(date, cycleB);
    long differenceA = differenceId(cycleA);
    long differenceB = differenceId(cycleB);
    new TriageService(target, metrics)
        .classify(differenceB, "APPROVED_FIX", "Q-01", null, "operator-subject");

    assertThat(differenceA).isNotEqualTo(differenceB);
    assertThat(classification(differenceA)).isEqualTo("UNTRIAGED");
    assertThat(classification(differenceB)).isEqualTo("APPROVED_FIX");
    assertThat(cycleStatus(cycleA)).isEqualTo("DIFFERENCES");
    assertThat(
            target.queryForObject(
                "SELECT count(*) FROM pas_batch.parallel_run_difference "
                    + "WHERE business_date = ? AND present AND artifact = 'PREMIUMS'",
                Integer.class,
                date))
        .isEqualTo(2);
  }

  @Test
  void cleanCycleAllowsApprovedFixButNotUntriagedDifference() throws Exception {
    seedSource(1);
    LocalDate firstDate = LocalDate.of(2025, 1, 1);
    LocalDate differenceDate = firstDate.plusDays(1);
    LocalDate lastDate = firstDate.plusDays(2);
    sourceJdbc.update(
        "UPDATE acmeins.premiums SET calc_date = ? WHERE policy_number = ? AND coverage_seq = 2",
        differenceDate,
        "POL000000001");
    Path root = Files.createTempDirectory("pas-parallel-run-it");
    Path legacy = root.resolve("legacy");
    Path extracts = root.resolve("extracts");
    for (LocalDate date : new LocalDate[] {firstDate, differenceDate, lastDate}) {
      Files.createDirectories(legacy.resolve(date.toString()));
      for (String job : new String[] {"dailyExtractJob", "monthlyExposureJob"}) {
        Files.createDirectories(extracts.resolve(job).resolve(date.toString()).resolve("1"));
      }
    }
    MigrationProperties runProperties = withExtractPaths(legacy, extracts);
    TriageService triage = new TriageService(target, metrics);
    ParallelRunService service =
        new ParallelRunService(target, source, runProperties, new ObjectMapper(), metrics, triage);
    UUID cycleId = UUID.randomUUID();
    target.update(
        """
        INSERT INTO pas_batch.parallel_run_cycle
            (cycle_id, name, start_date, end_date, created_by, status)
        VALUES (?, 'migration IT', ?, ?, 'integration-test', 'RUNNING')
        """,
        cycleId,
        firstDate,
        lastDate);
    copyPremiumsToShadow(firstDate, 11L);
    service.compare(firstDate, cycleId);

    copyPremiumsToShadow(differenceDate, 11L);
    target.update(
        "UPDATE pas_shadow.premiums SET total_premium = total_premium + 10 "
            + "WHERE business_date = ? AND policy_number = 'POL000000001' AND coverage_seq = 2",
        differenceDate);
    service.compare(differenceDate, cycleId);
    service.compare(lastDate, cycleId);
    long differenceId =
        target.queryForObject(
            "SELECT id FROM pas_batch.parallel_run_difference "
                + "WHERE cycle_id = ? AND present AND artifact = 'PREMIUMS'",
            Long.class,
            cycleId);
    triage.classify(differenceId, "APPROVED_FIX", "Q-01", null, "operator-subject");

    assertThat(cycleStatus(cycleId)).isEqualTo("CLEAN");
    assertThat(
            target.queryForObject(
                "SELECT count(*) FROM pas_batch.parallel_run_triage_log "
                    + "WHERE difference_id = ? AND triaged_by = 'operator-subject'",
                Integer.class,
                differenceId))
        .isEqualTo(1);

    target.update(
        "UPDATE pas_shadow.premiums SET total_premium = total_premium + 1 "
            + "WHERE business_date = ? AND policy_number = 'POL000000001' AND coverage_seq = 2",
        differenceDate);
    service.compare(differenceDate, cycleId);
    assertThat(cycleStatus(cycleId)).isEqualTo("DIFFERENCES");
    assertThat(
            target.queryForObject(
                "SELECT classification FROM pas_batch.parallel_run_difference WHERE id = ?",
                String.class,
                differenceId))
        .isEqualTo("UNTRIAGED");
  }

  private UUID createOneDateCycle(LocalDate date, String name) {
    UUID cycleId = UUID.randomUUID();
    target.update(
        """
        INSERT INTO pas_batch.parallel_run_cycle
            (cycle_id, name, start_date, end_date, created_by, status)
        VALUES (?, ?, ?, ?, 'integration-test', 'RUNNING')
        """,
        cycleId,
        name,
        date,
        date);
    return cycleId;
  }

  private long differenceId(UUID cycleId) {
    return target.queryForObject(
        "SELECT id FROM pas_batch.parallel_run_difference "
            + "WHERE cycle_id = ? AND present AND artifact = 'PREMIUMS'",
        Long.class,
        cycleId);
  }

  private String classification(long differenceId) {
    return target.queryForObject(
        "SELECT classification FROM pas_batch.parallel_run_difference WHERE id = ?",
        String.class,
        differenceId);
  }

  private void copyPremiumsToShadow(LocalDate businessDate, long jobInstanceId) {
    for (var row :
        sourceJdbc.queryForList(
            """
            SELECT policy_number, coverage_seq, term_effective_date, term_expiry_date, base_rate,
                   territory_factor, class_factor, experience_mod, schedule_mod, discount_pct,
                   surcharge_amt, tax_amt, total_premium, installment_code, installment_amt,
                   calc_date, calc_by
            FROM acmeins.premiums WHERE calc_date = ?
            """,
            businessDate)) {
      target.update(
          """
          INSERT INTO pas_shadow.premiums
              (policy_number, coverage_seq, term_effective_date, term_expiry_date, base_rate,
               territory_factor, class_factor, experience_mod, schedule_mod, discount_pct,
               surcharge_amt, tax_amt, total_premium, installment_code, installment_amt,
               calc_date, calc_by, business_date, job_instance_id)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          row.get("policy_number"),
          row.get("coverage_seq"),
          row.get("term_effective_date"),
          row.get("term_expiry_date"),
          row.get("base_rate"),
          row.get("territory_factor"),
          row.get("class_factor"),
          row.get("experience_mod"),
          row.get("schedule_mod"),
          row.get("discount_pct"),
          row.get("surcharge_amt"),
          row.get("tax_amt"),
          row.get("total_premium"),
          row.get("installment_code"),
          row.get("installment_amt"),
          row.get("calc_date"),
          row.get("calc_by"),
          businessDate,
          jobInstanceId);
    }
  }

  private String cycleStatus(UUID cycleId) {
    return target.queryForObject(
        "SELECT status FROM pas_batch.parallel_run_cycle WHERE cycle_id = ?",
        String.class,
        cycleId);
  }

  private MigrationProperties withExtractPaths(Path legacy, Path extracts) {
    return new MigrationProperties(
        properties.enabled(),
        properties.source(),
        properties.bulk(),
        properties.deltaSync(),
        properties.reverseSync(),
        properties.recon(),
        new MigrationProperties.ParallelRun(legacy.toString(), extracts.toString()));
  }
}
