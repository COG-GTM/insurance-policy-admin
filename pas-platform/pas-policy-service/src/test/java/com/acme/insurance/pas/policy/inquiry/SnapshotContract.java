package com.acme.insurance.pas.policy.inquiry;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java port of {@code compat/api/check_api.py#compare}: every snapshot field must exist with the
 * same JSON type and value (volatile fields type-only), arrays keep length and order, additive
 * fields are allowed.
 */
final class SnapshotContract {

  static final Set<String> VOLATILE = Set.of("lastUpdated");

  private SnapshotContract() {}

  static List<String> compare(JsonNode expected, JsonNode actual) {
    List<String> problems = new ArrayList<>();
    compare(expected, actual, "$", problems);
    return problems;
  }

  private static void compare(JsonNode exp, JsonNode act, String path, List<String> problems) {
    if (exp.isObject()) {
      if (!act.isObject()) {
        problems.add(path + ": expected object, got " + act.getNodeType());
        return;
      }
      for (Iterator<Map.Entry<String, JsonNode>> it = exp.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        String p = path + "." + e.getKey();
        if (!act.has(e.getKey())) {
          problems.add(p + ": missing");
        } else if (VOLATILE.contains(e.getKey())) {
          if (!sameType(e.getValue(), act.get(e.getKey()))) {
            problems.add(p + ": type changed");
          }
        } else {
          compare(e.getValue(), act.get(e.getKey()), p, problems);
        }
      }
    } else if (exp.isArray()) {
      if (!act.isArray() || act.size() != exp.size()) {
        problems.add(path + ": array length/type changed");
        return;
      }
      for (int i = 0; i < exp.size(); i++) {
        compare(exp.get(i), act.get(i), path + "[" + i + "]", problems);
      }
    } else if (!sameType(exp, act)) {
      problems.add(path + ": type " + exp.getNodeType() + " -> " + act.getNodeType());
    } else if (exp.isNumber()
        ? exp.decimalValue().compareTo(act.decimalValue()) != 0
        : !exp.equals(act)) {
      problems.add(path + ": " + exp + " -> " + act);
    }
  }

  /** Python json types: int and float are distinct, null is its own type. */
  private static boolean sameType(JsonNode a, JsonNode b) {
    if (a.isNumber() && b.isNumber()) {
      return a.isIntegralNumber() == b.isIntegralNumber();
    }
    return a.getNodeType() == b.getNodeType();
  }
}
