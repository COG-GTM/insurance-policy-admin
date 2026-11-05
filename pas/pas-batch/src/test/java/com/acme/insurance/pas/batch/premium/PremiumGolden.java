package com.acme.insurance.pas.batch.premium;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads the COBOL golden files of parity suite {@code prembat-batch-control} (AB-520 harness). */
final class PremiumGolden {

  static final Path DIR = Path.of("../../parity/golden/prembat-batch-control");

  private PremiumGolden() {}

  static JsonNode load(String caseId) {
    try {
      return new ObjectMapper().readTree(DIR.resolve(caseId + ".json").toFile());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The PREMRPT records of a case, as the harness stores them (non-printables as \xNN). */
  static List<String> report(String caseId) {
    List<String> lines = new ArrayList<>();
    load(caseId).path("artifacts").path("PREMRPT").forEach(l -> lines.add(l.asText()));
    return lines;
  }

  /** The harness's artifact encoding: bytes outside printable ASCII as \xNN, right-trimmed. */
  static String escapeAndTrim(String record) {
    StringBuilder out = new StringBuilder();
    for (char c : record.replaceAll(" +$", "").toCharArray()) {
      if (c < 0x20 || c > 0x7E) {
        out.append(String.format("\\x%02X", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
