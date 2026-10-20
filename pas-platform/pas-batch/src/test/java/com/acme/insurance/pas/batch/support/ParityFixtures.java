package com.acme.insurance.pas.batch.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.PGConnection;

/** Loads the AB-527 extract parity fixtures ({@code parity/extracts/}) into PostgreSQL. */
public final class ParityFixtures {

  /** One entry of {@code parity/extracts/runs.json}. */
  public record Run(String id, String job, LocalDate businessDate) {}

  private ParityFixtures() {}

  public static Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("parity/extracts/runs.json"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException("parity/extracts/runs.json not found above working dir");
    }
    return dir;
  }

  public static Path extractsDir() {
    return repoRoot().resolve("parity/extracts");
  }

  public static List<Run> runs() throws IOException {
    JsonNode root = new ObjectMapper().readTree(extractsDir().resolve("runs.json").toFile());
    List<Run> runs = new ArrayList<>();
    for (JsonNode run : root.get("runs")) {
      runs.add(
          new Run(
              run.get("id").asText(),
              run.get("job").asText(),
              LocalDate.parse(run.get("businessDate").asText())));
    }
    return runs;
  }

  public static Run run(String id) throws IOException {
    return runs().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
  }

  public static Path golden(Run run, String fileName) {
    return extractsDir().resolve("golden").resolve(run.id()).resolve(fileName);
  }

  /** Applies the pas-policy-service migrations (schema {@code pas}) the extracts read from. */
  public static void migratePasSchema(DataSource dataSource) {
    Path migrations =
        repoRoot().resolve("pas-platform/pas-policy-service/src/main/resources/db/migration");
    Flyway.configure()
        .dataSource(dataSource)
        .schemas("pas")
        .defaultSchema("pas")
        .createSchemas(true)
        .locations("filesystem:" + migrations)
        .load()
        .migrate();
  }

  /** Replaces pas.policies / pas.coverages with the run's snapshot. */
  public static void load(DataSource dataSource, Run run) throws SQLException, IOException {
    Path dir = extractsDir().resolve("fixtures").resolve(run.id());
    try (Connection conn = dataSource.getConnection()) {
      try (Statement st = conn.createStatement()) {
        st.execute("TRUNCATE pas.policies CASCADE");
      }
      copy(conn, "pas.policies", dir.resolve("policies.csv"));
      copy(conn, "pas.coverages", dir.resolve("coverages.csv"));
    }
  }

  private static void copy(Connection conn, String table, Path csv)
      throws SQLException, IOException {
    String header;
    try (var lines = Files.lines(csv, StandardCharsets.UTF_8)) {
      header = lines.findFirst().orElseThrow();
    }
    String sql =
        "COPY " + table + " (" + header + ") FROM STDIN WITH (FORMAT csv, HEADER true, NULL '\\N')";
    try (Reader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
      conn.unwrap(PGConnection.class).getCopyAPI().copyIn(sql, reader);
    }
  }
}
