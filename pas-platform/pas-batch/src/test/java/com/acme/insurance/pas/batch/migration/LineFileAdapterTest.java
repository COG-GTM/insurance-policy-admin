package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.extract.ExtractOutputs;
import com.acme.insurance.pas.batch.extract.ExtractProperties;
import com.acme.insurance.pas.batch.extract.ExtractRecordLayouts;
import com.acme.insurance.pas.batch.migration.parallelrun.LineFileAdapter;
import com.acme.insurance.pas.batch.migration.parallelrun.OutputAdapter;
import com.acme.insurance.pas.batch.migration.parallelrun.ParallelRunService;
import com.acme.insurance.pas.batch.migration.report.CheckReport;
import com.acme.insurance.pas.batch.migration.triage.TriageRegister;
import com.acme.insurance.pas.batch.support.ParityFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Parallel-run file diff against the committed AB-527 goldens with an injected byte change. */
class LineFileAdapterTest {

  static final LocalDate DAY = LocalDate.of(2026, 10, 12);

  @TempDir Path legacyDir;
  @TempDir Path newDir;

  @Test
  void detectsChangedFieldMissingAndExtraRecords() throws Exception {
    Path golden =
        ParityFixtures.extractsDir().resolve("golden/daily-2026-10-12/policy_extract.dat");
    Path legacy = legacyDir.resolve("dailyExtractJob/2026-10-12/policy_extract.dat");
    Path candidate = newDir.resolve("dailyExtractJob/2026-10-12/policy_extract.dat");
    Files.createDirectories(legacy.getParent());
    Files.createDirectories(candidate.getParent());
    Files.copy(golden, legacy);
    String[] records = Files.readString(golden, StandardCharsets.ISO_8859_1).split("\r\n");
    int premium = ExtractRecordLayouts.POLICY_EXTRACT.offsetOf("TOTAL_PREMIUM");
    char[] first = records[0].toCharArray();
    first[premium] = first[premium] == '0' ? '1' : '0';
    records[0] = new String(first);
    String removed = records[1].substring(0, 12);
    records[1] = "POL-EXTRA001" + records[1].substring(12);
    Files.writeString(
        candidate, String.join("\r\n", records) + "\r\n", StandardCharsets.ISO_8859_1);

    ExtractOutputs outputs = new ExtractOutputs(extractProperties(newDir));
    OutputAdapter adapter =
        LineFileAdapter.fixedWidth(
            "dailyExtractJob",
            "policy_extract.dat",
            legacyDir,
            outputs,
            d -> true,
            ExtractRecordLayouts.POLICY_EXTRACT,
            "POLICY_NUMBER");
    CheckReport report =
        new ParallelRunService(
                List.of(adapter), TriageRegister.empty(), List.of(), Clock.systemUTC())
            .run(DAY, "unit");

    assertThat(report.differences())
        .extracting(d -> d.kind() + ":" + d.key() + ":" + d.fields())
        .containsExactlyInAnyOrder(
            "CHANGED:" + records[0].substring(0, 12).strip() + ":[TOTAL_PREMIUM]",
            "MISSING_IN_NEW:" + removed.strip() + ":[]",
            "EXTRA_IN_NEW:POL-EXTRA001:[]");
    assertThat(report.unexplained()).isEqualTo(3);
    assertThat(report.status()).isEqualTo("FAIL");
  }

  private static ExtractProperties extractProperties(Path dir) {
    return new ExtractProperties(dir);
  }
}
