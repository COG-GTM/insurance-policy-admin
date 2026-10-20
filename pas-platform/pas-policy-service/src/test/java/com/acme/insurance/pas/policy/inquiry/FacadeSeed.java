package com.acme.insurance.pas.policy.inquiry;

import com.acme.insurance.pas.policy.persistence.PolicyHolderEntity;
import com.acme.insurance.pas.policy.persistence.PolicyHolderRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Replays {@code java-facade/src/main/resources/data.sql} (the data the contract snapshot was
 * recorded from) into the {@code pas} schema. Policy holders go through the JPA entity so AB-521
 * PII encryption applies.
 */
final class FacadeSeed {

  static final Path DATA_SQL = Path.of("../../java-facade/src/main/resources/data.sql");

  private static final Pattern MERGE =
      Pattern.compile(
          "MERGE\\s+INTO\\s+ACMEINS\\.(\\w+)\\s*\\(([^)]*)\\)\\s*KEY\\s*\\([^)]*\\)\\s*VALUES\\s*\\((.*?)\\)\\s*;",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private FacadeSeed() {}

  record Row(String table, List<String> columns, String valuesSql, List<String> values) {}

  static List<Row> rows() {
    String sql;
    try {
      sql = Files.readString(DATA_SQL).replaceAll("(?m)^\\s*--.*$", "");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<Row> rows = new ArrayList<>();
    Matcher m = MERGE.matcher(sql);
    while (m.find()) {
      List<String> columns =
          List.of(m.group(2).split(",")).stream()
              .map(c -> c.strip().toLowerCase(Locale.ROOT))
              .toList();
      List<String> values = splitValues(m.group(3));
      if (columns.size() != values.size()) {
        throw new IllegalStateException("Column/value mismatch in data.sql: " + m.group());
      }
      rows.add(new Row(m.group(1).toLowerCase(Locale.ROOT), columns, m.group(3), values));
    }
    return rows;
  }

  static void load(JdbcTemplate jdbc, PolicyHolderRepository holders) {
    for (Row row : rows()) {
      if (row.table().equals("policy_holders")) {
        holders.saveAndFlush(holder(row));
      } else {
        jdbc.update(
            "INSERT INTO pas."
                + row.table()
                + " ("
                + String.join(", ", row.columns())
                + ") VALUES ("
                + row.valuesSql()
                + ")");
      }
    }
  }

  private static PolicyHolderEntity holder(Row row) {
    PolicyHolderEntity h = new PolicyHolderEntity();
    // H2 column defaults in the facade schema; the entity writes these columns explicitly.
    h.setCreatedDate(LocalDate.now());
    h.setLastUpdated(LocalDateTime.now());
    for (int i = 0; i < row.columns().size(); i++) {
      String v = unquote(row.values().get(i));
      switch (row.columns().get(i)) {
        case "cust_id" -> h.setCustId(v);
        case "cust_type" -> h.setCustType(v);
        case "last_name" -> h.setLastName(v);
        case "first_name" -> h.setFirstName(v);
        case "middle_init" -> h.setMiddleInit(v);
        case "company_name" -> h.setCompanyName(v);
        case "addr_line1" -> h.setAddrLine1(v);
        case "addr_line2" -> h.setAddrLine2(v);
        case "city" -> h.setCity(v);
        case "state_code" -> h.setStateCode(v);
        case "zip_code" -> h.setZipCode(v);
        case "phone" -> h.setPhone(v);
        case "email" -> h.setEmail(v);
        case "date_of_birth" -> h.setDateOfBirth(LocalDate.parse(v));
        case "credit_score" -> h.setCreditScore(Integer.valueOf(v));
        case "risk_tier" -> h.setRiskTier(v);
        case "tax_id" -> h.setTaxId(v);
        default ->
            throw new IllegalStateException("Unmapped holder column " + row.columns().get(i));
      }
    }
    return h;
  }

  private static List<String> splitValues(String values) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (char c : values.toCharArray()) {
      if (c == '\'') {
        quoted = !quoted;
      }
      if (c == ',' && !quoted) {
        out.add(current.toString().strip());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    out.add(current.toString().strip());
    return out;
  }

  private static String unquote(String value) {
    if (value.equalsIgnoreCase("NULL")) {
      return null;
    }
    return value.startsWith("'")
        ? value.substring(1, value.length() - 1).replace("''", "'")
        : value;
  }
}
