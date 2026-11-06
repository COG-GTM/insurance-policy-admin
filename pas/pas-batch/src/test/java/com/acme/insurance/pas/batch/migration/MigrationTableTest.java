package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The migrated column lists are exactly the columns of the frozen DB2 DDL, in DDL order. */
class MigrationTableTest {

  static final Path DDL = Path.of("..", "..", "sql", "ddl", "create-tables.sql");

  @ParameterizedTest
  @EnumSource(MigrationTable.class)
  void columnsMatchTheFrozenDdl(MigrationTable table) throws IOException {
    assertThat(table.columns()).isEqualTo(ddlColumns(table.name()));
    assertThat(table.columns()).containsAll(table.keyColumns());
  }

  @Test
  void parentsComeBeforeChildren() {
    List<MigrationTable> order = List.of(MigrationTable.values());
    for (MigrationTable child :
        List.of(
            MigrationTable.COVERAGES,
            MigrationTable.PREMIUMS,
            MigrationTable.ENDORSEMENTS,
            MigrationTable.UNDERWRITING_DECISIONS)) {
      assertThat(order.indexOf(MigrationTable.POLICIES)).isLessThan(order.indexOf(child));
    }
  }

  @Test
  void statementsUsePortableSyntaxAndNeverWriteDecisionId() {
    assertThat(TableSynchronizer.insertSql(MigrationTable.UNDERWRITING_DECISIONS))
        .doesNotContain("DECISION_ID")
        .startsWith("INSERT INTO ACMEINS.UNDERWRITING_DECISIONS (POLICY_NUMBER, DECISION_DATE,");
    assertThat(TableSynchronizer.updateSql(MigrationTable.COVERAGES))
        .endsWith("WHERE POLICY_NUMBER = ? AND SEQUENCE_NUM = ?");
    assertThat(TableSynchronizer.deleteSql(MigrationTable.PREMIUMS))
        .isEqualTo(
            "DELETE FROM ACMEINS.PREMIUMS WHERE POLICY_NUMBER = ? AND COVERAGE_SEQ = ?"
                + " AND TERM_EFFECTIVE_DATE = ?");
  }

  static List<String> ddlColumns(String table) throws IOException {
    String ddl = Files.readString(DDL).toUpperCase(Locale.ROOT);
    Matcher start = Pattern.compile("CREATE TABLE ACMEINS\\." + table + "\\s*\\(").matcher(ddl);
    assertThat(start.find()).as("table %s in DDL", table).isTrue();
    List<String> columns = new ArrayList<>();
    for (String line : ddl.substring(start.end()).split("\n")) {
      String trimmed = line.strip();
      if (trimmed.startsWith(")")) {
        break;
      }
      Matcher column = Pattern.compile("^([A-Z][A-Z0-9_]*)\\s+[A-Z]").matcher(trimmed);
      if (column.find()
          && !List.of("CONSTRAINT", "PRIMARY", "FOREIGN", "REFERENCES", "UNIQUE", "CHECK")
              .contains(column.group(1))) {
        columns.add(column.group(1));
      }
    }
    return columns;
  }
}
