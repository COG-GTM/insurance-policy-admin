package com.acme.insurance.pas.batch.extract;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.stereotype.Component;

/** Shared helpers for the extract/exposure tasklets: output locations, job parameters, JDBC. */
@Component
public class ExtractOutputs {

  /** z/OS FTP ASCII-mode transfer renders CCSID 37 text as ISO-8859-1. */
  public static final Charset RECORD_CHARSET = StandardCharsets.ISO_8859_1;

  public static final String BUSINESS_DATE = "businessDate";

  private final ExtractProperties properties;

  public ExtractOutputs(ExtractProperties properties) {
    this.properties = properties;
  }

  public Path location(String jobName, LocalDate businessDate, String fileName) {
    return properties
        .outputDir()
        .resolve(jobName)
        .resolve(businessDate.toString())
        .resolve(fileName);
  }

  public static LocalDate businessDate(ChunkContext chunkContext) {
    Object value = chunkContext.getStepContext().getJobParameters().get(BUSINESS_DATE);
    if (value instanceof LocalDate date) {
      return date;
    }
    if (value instanceof String text) {
      return LocalDate.parse(text);
    }
    throw new IllegalArgumentException(
        "job parameter '" + BUSINESS_DATE + "' (LocalDate) required");
  }

  public static Map<String, Object> queryParameters(LocalDate businessDate) {
    Map<String, Object> params = new java.util.HashMap<>(EbcdicCollation.parameters());
    params.put(BUSINESS_DATE, businessDate);
    return params;
  }

  /** DB2 CHAR/VARCHAR value without the blank padding; {@code null} stays {@code null}. */
  public static String text(ResultSet rs, String column) throws SQLException {
    String value = rs.getString(column);
    if (value == null) {
      return null;
    }
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == ' ') {
      end--;
    }
    return value.substring(0, end);
  }

  public static LocalDate date(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, LocalDate.class);
  }
}
