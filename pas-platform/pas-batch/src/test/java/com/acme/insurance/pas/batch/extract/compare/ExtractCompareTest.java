package com.acme.insurance.pas.batch.extract.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtractCompareTest {
  @TempDir Path directory;

  @Test
  void identicalFilesMatchAndSidecarsAreIgnored() throws Exception {
    Path expected = Files.createDirectory(directory.resolve("expected"));
    Path actual = Files.createDirectory(directory.resolve("actual"));
    byte[] record = fixedRecord("POLICY001");
    Files.write(expected.resolve("policy_extract.dat"), record);
    Files.write(actual.resolve("policy_extract.dat"), record);
    Files.writeString(expected.resolve("policy_extract.dat.done"), "ignored\n");
    Files.writeString(actual.resolve("policy_extract.dat.sha256"), "ignored\n");
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    int exit =
        ExtractCompare.compare(
            new String[] {expected.toString(), actual.toString()},
            new PrintStream(output),
            System.err);

    assertThat(exit).isZero();
    assertThat(output.toString(StandardCharsets.UTF_8))
        .contains("MATCH policy_extract.dat sha256=");
  }

  @Test
  void pairsDatedMonthlyLocalNameWithCanonicalExpectedName() throws Exception {
    Path expected = Files.createDirectory(directory.resolve("monthly-expected"));
    Path actual = Files.createDirectory(directory.resolve("monthly-actual"));
    String content = "HOM,B001,FIRE,,A,2,10.00,1.00,0.00\r\n";
    Files.writeString(
        expected.resolve("exposure_monthly.csv"), content, StandardCharsets.ISO_8859_1);
    Files.writeString(
        actual.resolve("exposure_monthly.D251001.csv"), content, StandardCharsets.ISO_8859_1);
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    int exit =
        ExtractCompare.compare(
            new String[] {expected.toString(), actual.toString()},
            new PrintStream(output),
            System.err);

    assertThat(exit).isZero();
    assertThat(output.toString(StandardCharsets.UTF_8))
        .contains("MATCH exposure_monthly.csv sha256=");
  }

  @Test
  void rejectsMultipleFilesMappedToTheSameMonthlyName() throws Exception {
    Path expected = Files.createDirectory(directory.resolve("monthly-duplicate"));
    Path actual = Files.createDirectory(directory.resolve("monthly-unambiguous"));
    Files.writeString(expected.resolve("exposure_monthly.csv"), "first");
    Files.writeString(expected.resolve("exposure_monthly.D251001.csv"), "second");
    Files.writeString(actual.resolve("exposure_monthly.csv"), "first");
    ByteArrayOutputStream error = new ByteArrayOutputStream();

    int exit =
        ExtractCompare.compare(
            new String[] {expected.toString(), actual.toString()},
            System.out,
            new PrintStream(error));

    assertThat(exit).isEqualTo(2);
    assertThat(error.toString(StandardCharsets.UTF_8))
        .contains("exposure_monthly.csv")
        .contains("exposure_monthly.D251001.csv");
  }

  @Test
  void fixedWidthDifferenceReportsFieldWithoutPrintingValues() throws Exception {
    Path expected = Files.createDirectory(directory.resolve("fixed-expected"));
    Path actual = Files.createDirectory(directory.resolve("fixed-actual"));
    Files.write(expected.resolve("policy_extract.dat"), fixedRecord("POLICY001"));
    Files.write(actual.resolve("policy_extract.dat"), fixedRecord("CLAIM9999"));
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    int exit =
        ExtractCompare.compare(
            new String[] {expected.toString(), actual.toString()},
            new PrintStream(output),
            System.err);
    String text = output.toString(StandardCharsets.UTF_8);

    assertThat(exit).isEqualTo(1);
    assertThat(text).contains("record 1").contains("POLICY_NUMBER");
    assertThat(text).doesNotContain("POLICY001").doesNotContain("CLAIM9999");
  }

  @Test
  void reportsMissingExtraLengthAndCsvColumns() throws Exception {
    Path expected = Files.createDirectory(directory.resolve("csv-expected"));
    Path actual = Files.createDirectory(directory.resolve("csv-actual"));
    Files.writeString(expected.resolve("report.csv"), "same\n", StandardCharsets.ISO_8859_1);
    Files.writeString(actual.resolve("report.csv"), "same-long\n", StandardCharsets.ISO_8859_1);
    Files.writeString(
        expected.resolve("exposure_monthly.csv"),
        "HOM,B001,FIRE,,A,2,10.00,1.00,0.00\r\n",
        StandardCharsets.ISO_8859_1);
    Files.writeString(
        actual.resolve("exposure_monthly.csv"),
        "HOM,B001,FIRE,,B,2,10.00,1.00,0.00\r\n",
        StandardCharsets.ISO_8859_1);
    Files.writeString(expected.resolve("missing.csv"), "x");
    Files.writeString(actual.resolve("extra.csv"), "x");
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    int exit =
        ExtractCompare.compare(
            new String[] {expected.toString(), actual.toString()},
            new PrintStream(output),
            System.err);
    String text = output.toString(StandardCharsets.UTF_8);

    assertThat(exit).isEqualTo(1);
    assertThat(text).contains("MISSING missing.csv").contains("EXTRA extra.csv");
    assertThat(text).contains("length expected").contains("column CLASS_CODE");
    assertThat(text).doesNotContain("HOM,B001").doesNotContain("HOM");
  }

  @Test
  void flagsCsvRecordOverMaximumLength() throws Exception {
    Path expected = Files.createDirectory(directory.resolve("long-csv-expected"));
    Path actual = Files.createDirectory(directory.resolve("long-csv-actual"));
    Files.writeString(
        expected.resolve("exposure_monthly.csv"),
        "x".repeat(301) + "\r\n",
        StandardCharsets.ISO_8859_1);
    Files.writeString(
        actual.resolve("exposure_monthly.csv"),
        "x".repeat(301) + "\r\n",
        StandardCharsets.ISO_8859_1);
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    int exit =
        ExtractCompare.compare(
            new String[] {expected.toString(), actual.toString()},
            new PrintStream(output),
            System.err);

    assertThat(exit).isEqualTo(1);
    assertThat(output.toString(StandardCharsets.UTF_8))
        .contains("LAYOUT MISMATCH")
        .doesNotContain("x".repeat(50));
  }

  @Test
  void invalidArgumentsReturnUsageCode() {
    assertThat(
            ExtractCompare.compare(
                new String[0], System.out, new PrintStream(new ByteArrayOutputStream())))
        .isEqualTo(2);
  }

  private static byte[] fixedRecord(String value) {
    byte[] record = new byte[202];
    java.util.Arrays.fill(record, (byte) ' ');
    byte[] text = value.getBytes(StandardCharsets.ISO_8859_1);
    System.arraycopy(text, 0, record, 0, text.length);
    record[200] = '\r';
    record[201] = '\n';
    return record;
  }
}
