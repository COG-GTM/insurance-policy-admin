package com.acme.insurance.pas.parity;

import java.util.List;
import java.util.regex.Pattern;

/**
 * An allowed difference between the Java result and the COBOL golden output, from {@code
 * parity/known-differences.json}. Only quirks with a 'fix' decision qualify; the link to the
 * decision is validated by {@code make parity-check}.
 */
public record KnownDifference(
    String id,
    String suite,
    List<String> cases,
    List<String> paths,
    String quirk,
    String decision,
    String ticket,
    String signOff,
    String reason) {

  public KnownDifference {
    cases = List.copyOf(cases);
    paths = List.copyOf(paths);
  }

  public boolean approved() {
    return signOff != null && signOff.startsWith("approved:");
  }

  public boolean covers(String suiteName, String caseId, String path) {
    return suite.equals(suiteName)
        && (cases.contains("*") || cases.contains(caseId))
        && paths.stream().anyMatch(glob -> glob(glob).matcher(path).matches());
  }

  static Pattern glob(String glob) {
    StringBuilder regex = new StringBuilder();
    for (String part : glob.split("\\*", -1)) {
      if (!regex.isEmpty()) {
        regex.append(".*");
      }
      regex.append(Pattern.quote(part));
    }
    return Pattern.compile(regex.toString());
  }
}
