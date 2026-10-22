package com.acme.insurance.pas.batch.migration.parallel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class QuirkRegister {
  private final Map<String, String> decisions;

  public QuirkRegister(Map<String, String> decisions) {
    this.decisions = Map.copyOf(decisions);
  }

  public static QuirkRegister load() {
    var input =
        QuirkRegister.class.getClassLoader().getResourceAsStream("quirks/quirk-register.md");
    if (input == null) {
      throw new IllegalStateException("Classpath resource quirks/quirk-register.md is missing");
    }
    try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      return parse(reader);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load quirk register", exception);
    }
  }

  public static QuirkRegister parse(BufferedReader reader) throws IOException {
    Map<String, String> decisions = new LinkedHashMap<>();
    String line;
    while ((line = reader.readLine()) != null) {
      if (!line.startsWith("|")) {
        continue;
      }
      String[] cells = line.split("\\|", -1);
      if (cells.length < 7) {
        continue;
      }
      String id = cells[1].trim();
      if (!id.matches("Q-\\d{2}")) {
        continue;
      }
      decisions.put(id, cells[5].trim());
    }
    return new QuirkRegister(decisions);
  }

  public boolean approvesFix(String quirkId) {
    String decision = decisions.get(quirkId);
    return decision != null && decision.contains("Fix");
  }

  public Map<String, String> decisions() {
    return decisions;
  }
}
