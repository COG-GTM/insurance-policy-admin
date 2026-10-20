package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.support.ParityFixtures;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Every migrated table reads exactly the columns of the DB2 DDL ({@code sql/ddl}). */
class SourceSchemaDriftTest {

  @Test
  void migrationColumnsMatchDb2Ddl() throws Exception {
    String ddl = Files.readString(ParityFixtures.repoRoot().resolve("sql/ddl/create-tables.sql"));
    for (MigrationTable table : MigrationTable.values()) {
      Matcher m =
          Pattern.compile(
                  "CREATE TABLE ACMEINS\\." + table.sourceTable() + " \\((.*?)\\n\\)",
                  Pattern.DOTALL)
              .matcher(ddl);
      assertThat(m.find()).as(table.sourceTable()).isTrue();
      Set<String> columns = new LinkedHashSet<>();
      for (String line : m.group(1).split("\\n")) {
        Matcher col = Pattern.compile("^\\s{4}([A-Z_0-9]+)\\s+[A-Z]").matcher(line);
        if (col.find() && !col.group(1).equals("CONSTRAINT")) {
          columns.add(col.group(1).toLowerCase(Locale.ROOT));
        }
      }
      assertThat(table.sourceColumns())
          .as(table.sourceTable())
          .containsExactlyInAnyOrderElementsOf(columns);
    }
  }
}
