package com.acme.insurance.pas.batch.migration.triage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classified differences ({@code parity/parallel-run/triage-register.json}). Same spirit as {@code
 * parity/known-differences.json}: an entry applies only when check, scope, key (glob), kind and
 * field match, and — if given — the exact legacy/new values. {@code APPROVED_FIX} entries must name
 * a {@code quirk} ({@code Q-nn}) and a {@code decision} of {@code
 * docs/modernization/quirk-register.md#q-nn} whose anchor exists. Invalid registers fail fast.
 * Anything that matches no entry stays {@link Classification#UNCLASSIFIED}.
 */
public final class TriageRegister {

  static final String QUIRK_REGISTER_PATH = "docs/modernization/quirk-register.md";
  private static final Pattern QUIRK = Pattern.compile("Q-(\\d{2})");
  private static final Pattern DECISION =
      Pattern.compile(Pattern.quote(QUIRK_REGISTER_PATH) + "#q-(\\d{2})");
  private static final Pattern ANCHOR = Pattern.compile("^### Q-(\\d{2})\\s*$", Pattern.MULTILINE);

  /** One register entry. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Entry(
      String id,
      String check,
      String scope,
      String key,
      String kind,
      String field,
      String legacy,
      String candidate,
      Classification classification,
      String quirk,
      String decision,
      String ticket,
      String owner,
      String rationale,
      String status) {}

  /** File shape: {@code {"comment": "...", "entries": [...]}}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record FileShape(String comment, List<Entry> entries) {}

  private final List<Entry> entries;
  private final List<Pattern> keyPatterns;

  public TriageRegister(List<Entry> entries, Set<String> knownQuirkIds) {
    List<String> errors = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Entry e : entries) {
      validate(e, knownQuirkIds, errors);
      if (e.id() != null && !ids.add(e.id())) {
        errors.add("duplicate triage id " + e.id());
      }
    }
    if (!errors.isEmpty()) {
      throw new IllegalStateException("invalid triage register: " + String.join("; ", errors));
    }
    this.entries = List.copyOf(entries);
    this.keyPatterns = this.entries.stream().map(e -> glob(e.key())).toList();
  }

  public static TriageRegister empty() {
    return new TriageRegister(List.of(), Set.of());
  }

  /** Loads the register; a missing file means "nothing triaged yet". */
  public static TriageRegister load(Path register, Path quirkRegister) throws IOException {
    Set<String> quirks = quirkIds(quirkRegister);
    if (!Files.exists(register)) {
      return new TriageRegister(List.of(), quirks);
    }
    FileShape shape = new ObjectMapper().readValue(register.toFile(), FileShape.class);
    return new TriageRegister(shape.entries() == null ? List.of() : shape.entries(), quirks);
  }

  /** {@code Q-nn} ids that have a {@code ### Q-nn} heading (stable {@code #q-nn} anchor). */
  public static Set<String> quirkIds(Path quirkRegister) throws IOException {
    Set<String> ids = new HashSet<>();
    Matcher m = ANCHOR.matcher(Files.readString(quirkRegister, StandardCharsets.UTF_8));
    while (m.find()) {
      ids.add("Q-" + m.group(1));
    }
    return ids;
  }

  public List<Entry> entries() {
    return entries;
  }

  public Difference classify(Difference d) {
    for (int i = 0; i < entries.size(); i++) {
      Entry e = entries.get(i);
      if (matches(e, keyPatterns.get(i), d)) {
        return d.classified(e.classification(), e.id(), reference(e));
      }
    }
    return d;
  }

  public List<Difference> classifyAll(List<Difference> differences) {
    return differences.stream().map(this::classify).toList();
  }

  private static boolean matches(Entry e, Pattern key, Difference d) {
    return e.check().equalsIgnoreCase(d.check())
        && e.scope().equals(d.scope())
        && key.matcher(d.key() == null ? "" : d.key()).matches()
        && (e.kind() == null || e.kind().equals(d.kind()))
        && (e.field() == null || d.fields().contains(e.field()))
        && (e.legacy() == null || e.legacy().equals(d.legacy()))
        && (e.candidate() == null || e.candidate().equals(d.candidate()));
  }

  private static String reference(Entry e) {
    return switch (e.classification()) {
      case APPROVED_FIX -> e.decision();
      case BUG -> e.ticket();
      default -> e.owner() == null ? e.rationale() : e.owner() + ": " + e.rationale();
    };
  }

  private static void validate(Entry e, Set<String> quirks, List<String> errors) {
    String id = e.id() == null ? "<no id>" : e.id();
    if (e.id() == null || e.id().isBlank()) {
      errors.add("entry without id");
    }
    if (e.check() == null || e.scope() == null || e.key() == null) {
      errors.add(id + ": check, scope and key are required");
    }
    if (e.classification() == null || e.classification() == Classification.UNCLASSIFIED) {
      errors.add(id + ": classification must be BUG, APPROVED_FIX or DATA_ISSUE");
      return;
    }
    if (blank(e.rationale())) {
      errors.add(id + ": rationale is required");
    }
    switch (e.classification()) {
      case APPROVED_FIX -> validateApprovedFix(e, id, quirks, errors);
      case BUG -> {
        if (blank(e.ticket())) {
          errors.add(id + ": BUG needs a ticket");
        }
      }
      case DATA_ISSUE -> {
        if (blank(e.owner())) {
          errors.add(id + ": DATA_ISSUE needs an owner");
        }
      }
      default -> {}
    }
  }

  private static void validateApprovedFix(
      Entry e, String id, Set<String> quirks, List<String> errors) {
    Matcher q = QUIRK.matcher(e.quirk() == null ? "" : e.quirk());
    Matcher d = DECISION.matcher(e.decision() == null ? "" : e.decision());
    if (!q.matches()) {
      errors.add(id + ": APPROVED_FIX needs quirk Q-nn");
    } else if (!quirks.contains(e.quirk())) {
      errors.add(id + ": " + e.quirk() + " is not in the quirk register");
    }
    if (!d.matches()) {
      errors.add(id + ": APPROVED_FIX decision must be " + QUIRK_REGISTER_PATH + "#q-nn");
    } else if (q.matches() && !q.group(1).equals(d.group(1))) {
      errors.add(id + ": decision anchor does not match " + e.quirk());
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static Pattern glob(String key) {
    if (key == null) {
      return Pattern.compile("");
    }
    StringBuilder regex = new StringBuilder();
    for (String part : key.split("\\*", -1)) {
      if (!regex.isEmpty()) {
        regex.append(".*");
      }
      regex.append(Pattern.quote(part));
    }
    return Pattern.compile(regex.toString(), Pattern.DOTALL);
  }

  @Override
  public String toString() {
    return "TriageRegister[" + entries.size() + " entries]".toLowerCase(Locale.ROOT);
  }
}
