package com.acme.insurance.pas.batch.migration.report;

import com.acme.insurance.pas.batch.migration.triage.Classification;
import com.acme.insurance.pas.batch.migration.triage.Difference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a {@link CheckReport} as JSON (machine-readable, every difference) and Markdown (for the
 * team channel / wiki, first {@value #MARKDOWN_ROWS} differences) under {@code
 * <reportDir>/<check>/<business-date>/}, and refreshes {@code <reportDir>/<check>/latest.*}.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "returns an immutable List.of / shared pool or mapper by design")
public final class ReportWriter {

  static final int MARKDOWN_ROWS = 200;

  /** Paths of the written report files. */
  public record Written(Path json, Path markdown) {}

  private final Path reportDir;
  private final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.INDENT_OUTPUT);

  public ReportWriter(Path reportDir) {
    this.reportDir = reportDir;
  }

  public ObjectMapper mapper() {
    return json;
  }

  public Written write(CheckReport report) {
    String check = report.checkType().name().toLowerCase(Locale.ROOT).replace('_', '-');
    Path dir = reportDir.resolve(check).resolve(report.businessDate().toString());
    try {
      Files.createDirectories(dir);
      Path jsonFile = dir.resolve(report.runId() + ".json");
      Path mdFile = dir.resolve(report.runId() + ".md");
      json.writeValue(jsonFile.toFile(), report);
      Files.writeString(mdFile, markdown(report), StandardCharsets.UTF_8);
      Files.copy(
          jsonFile,
          reportDir.resolve(check).resolve("latest.json"),
          StandardCopyOption.REPLACE_EXISTING);
      Files.copy(
          mdFile,
          reportDir.resolve(check).resolve("latest.md"),
          StandardCopyOption.REPLACE_EXISTING);
      return new Written(jsonFile, mdFile);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static String markdown(CheckReport r) {
    StringBuilder md = new StringBuilder();
    md.append("# ")
        .append(
            r.checkType() == CheckType.RECONCILIATION ? "DB2 ↔ PAS reconciliation" : "Parallel run")
        .append(" — ")
        .append(r.businessDate())
        .append("\n\n");
    md.append("**Status: ").append(r.status()).append("** · unexplained (BUG + UNCLASSIFIED): ");
    md.append(r.unexplained()).append(" · run `").append(r.runId()).append("`\n\n");
    md.append("| Classification | Count |\n|---|---:|\n");
    for (Classification c : Classification.values()) {
      md.append("| ").append(c).append(" | ").append(r.count(c)).append(" |\n");
    }
    md.append("\n**Provenance**\n\n");
    r.provenance().forEach(p -> md.append("- ").append(p).append('\n'));
    r.sections().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(e -> section(md, e.getKey(), e.getValue()));
    md.append("\n## Differences\n\n");
    if (r.differences().isEmpty()) {
      md.append("None.\n");
      return md.toString();
    }
    md.append("| Scope | Kind | Key | Fields | Legacy | New | Classification | Reference |\n");
    md.append("|---|---|---|---|---|---|---|---|\n");
    List<Difference> shown = r.differences().stream().limit(MARKDOWN_ROWS).toList();
    for (Difference d : shown) {
      md.append("| ")
          .append(cell(d.scope()))
          .append(" | ")
          .append(cell(d.kind()))
          .append(" | ")
          .append(cell(d.key()))
          .append(" | ")
          .append(cell(String.join(", ", d.fields())))
          .append(" | ")
          .append(cell(d.legacy()))
          .append(" | ")
          .append(cell(d.candidate()))
          .append(" | ")
          .append(d.classification())
          .append(d.triageId() == null ? "" : " (" + d.triageId() + ")")
          .append(" | ")
          .append(cell(d.reference()))
          .append(" |\n");
    }
    if (r.differences().size() > shown.size()) {
      md.append("\n… ")
          .append(r.differences().size() - shown.size())
          .append(" more in the JSON report.\n");
    }
    return md.toString();
  }

  private static void section(StringBuilder md, String name, Object value) {
    md.append("\n## ").append(name).append("\n\n");
    if (!(value instanceof List<?> rows) || rows.isEmpty() || !(rows.get(0) instanceof Record)) {
      md.append(cell(String.valueOf(value))).append('\n');
      return;
    }
    var components = rows.get(0).getClass().getRecordComponents();
    md.append('|');
    for (var c : components) {
      md.append(' ').append(c.getName()).append(" |");
    }
    md.append("\n|");
    md.append("---|".repeat(components.length)).append('\n');
    for (Object row : rows) {
      md.append('|');
      for (var c : components) {
        try {
          md.append(' ').append(cell(String.valueOf(c.getAccessor().invoke(row)))).append(" |");
        } catch (ReflectiveOperationException e) {
          throw new IllegalStateException(e);
        }
      }
      md.append('\n');
    }
  }

  private static String cell(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("|", "\\|").replace("\n", " ").replace("\r", "");
  }
}
