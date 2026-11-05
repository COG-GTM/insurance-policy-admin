import copy
import json
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_adr as ca  # noqa: E402

ADR = """# ADR 0099: Sample

- **Status:** Proposed
- **Date:** 2026-11-05
- **Ticket:** AB-518
- **New components:** none
- **Deciders:** ARB

## Context

Text.

## Decision drivers

Text.

## Decisions

### D1: Pick a database

**Decision.** PostgreSQL.

**Alternatives considered**

| Option | Outcome | Why |
|---|---|---|
| PostgreSQL | **Chosen** | fits |
| Stay on DB2 | Rejected | epic goal |

## Consequences

Text.
"""

COMPONENT = {
    "id": "DS-01", "name": "db", "kind": "data-store", "decision": "D1", "purpose": "p", "replaces": "r",
    "data": "d", "classification": "Restricted", "pii": True, "encryption": "e", "retention": "r",
    "owner": "o", "introduced_by": "AB-521", "reviews": {"security": "pending", "data_governance": "pending"},
}


class AdrStructureTest(unittest.TestCase):
    def test_valid_adr(self):
        errors, ds = ca.check_adr("x.md", ADR)
        self.assertEqual(errors, [])
        self.assertEqual(set(ds), {"D1"})

    def test_missing_alternatives(self):
        text = ADR.split("**Alternatives considered**")[0] + "## Consequences\n"
        errors, _ = ca.check_adr("x.md", text)
        self.assertTrue(any("no 'Alternatives considered'" in e for e in errors))

    def test_needs_rejected_alternative(self):
        errors, _ = ca.check_adr("x.md", ADR.replace("| Stay on DB2 | Rejected | epic goal |\n", ""))
        self.assertTrue(any("at least one rejected" in e for e in errors))

    def test_exactly_one_chosen(self):
        errors, _ = ca.check_adr("x.md", ADR.replace("| Stay on DB2 | Rejected |", "| Stay on DB2 | **Chosen** |"))
        self.assertTrue(any("exactly one option" in e for e in errors))

    def test_bad_status_and_missing_section(self):
        errors, _ = ca.check_adr("x.md", ADR.replace("Proposed", "Maybe").replace("## Consequences", "## Other"))
        self.assertTrue(any("invalid Status" in e for e in errors))
        self.assertTrue(any("'## Consequences'" in e for e in errors))


class RegisterRequirementTest(unittest.TestCase):
    LINKED = ADR.replace("- **New components:** none", "- **New components:** see [`0099-components.json`](0099-components.json)")

    def test_none_without_register(self):
        self.assertEqual(ca.register_errors("x.md", ADR, "0099", False), [])

    def test_none_with_register_fails(self):
        self.assertTrue(ca.register_errors("x.md", ADR, "0099", True))

    def test_components_without_register_fail(self):
        errs = ca.register_errors("x.md", self.LINKED, "0099", False)
        self.assertTrue(any("needs a component register" in e for e in errs))

    def test_linked_register_passes(self):
        self.assertEqual(ca.register_errors("x.md", self.LINKED, "0099", True), [])

    def test_header_must_link_register(self):
        text = ADR.replace("- **New components:** none", "- **New components:** several")
        self.assertTrue(any("must link" in e for e in ca.register_errors("x.md", text, "0099", True)))

    def test_missing_header_is_reported(self):
        errors, _ = ca.check_adr("x.md", ADR.replace("- **New components:** none\n", ""))
        self.assertTrue(any("'New components'" in e for e in errors))


class ComponentRegisterTest(unittest.TestCase):
    def errors(self, **changes):
        c = copy.deepcopy(COMPONENT)
        c.update(changes)
        return ca.validate_components("c.json", [c], {"D1"})

    def test_valid_component(self):
        self.assertEqual(self.errors(), [])

    def test_data_store_needs_data_governance(self):
        errs = self.errors(reviews={"security": "pending", "data_governance": "n/a"})
        self.assertTrue(any("data governance review" in e for e in errs))

    def test_every_component_needs_security(self):
        errs = self.errors(kind="platform", pii=False, classification="Internal",
                           reviews={"security": "n/a", "data_governance": "n/a"})
        self.assertTrue(any("security review" in e for e in errs))

    def test_unknown_decision_and_ticket(self):
        errs = self.errors(decision="D7", introduced_by="AB-999")
        self.assertTrue(any("decision D7" in e for e in errs))
        self.assertTrue(any("introduced_by" in e for e in errs))

    def test_review_format(self):
        self.assertEqual(self.errors(reviews={"security": "approved by J. Doe 2026-12-01",
                                              "data_governance": "pending"}), [])
        self.assertTrue(self.errors(reviews={"security": "ok", "data_governance": "pending"}))

    def test_duplicate_and_bad_id(self):
        errs = ca.validate_components("c.json", [COMPONENT, COMPONENT], {"D1"})
        self.assertTrue(any("duplicate id" in e for e in errs))
        self.assertTrue(any("id must match" in e for e in self.errors(id="DB-1")))

    def test_generated_block(self):
        text = "a\n<!-- BEGIN GENERATED: components -->\nold\n<!-- END GENERATED: components -->\nb"
        new = ca.replace_block(text, "components", ca.components_table([COMPONENT]))
        self.assertIn("| DS-01 | db | data-store |", new)
        self.assertNotIn("old", new)
        self.assertIsNone(ca.replace_block("no markers", "components", "x"))


class ReferenceTest(unittest.TestCase):
    REFS = ({"Q-13", "Q-518-1"}, {"FU-09"}, {"I-1", "I-2", "I-3"})

    def test_known_refs_pass(self):
        self.assertEqual(ca.reference_errors("x", "Q-13 Q-518-1 FU-09 I-1..I-3 AB-516 AB-530", self.REFS), [])

    def test_unknown_refs_fail(self):
        errs = ca.reference_errors("x", "Q-77 FU-99 I-1..I-4 AB-600", self.REFS)
        self.assertEqual(len(errs), 4)


class RepositoryTest(unittest.TestCase):
    def test_repository_adrs_are_valid(self):
        errors, _ = ca.run(write=False)
        self.assertEqual(errors, [])

    def test_ab518_scope_is_covered(self):
        text = ca.read("docs/adr/0001-pas-target-architecture.md")
        _, ds = ca.check_adr("adr", text)
        self.assertGreaterEqual(len(ds), 7)
        titles = dict(re.findall(r"^### (D\d+): (.+)$", text, re.M))
        for topic in ("Service boundaries", "Runtime", "Target database", "Messaging",
                      "Authentication", "Secure file transfer", "Strangler"):
            self.assertTrue(any(t.startswith(topic) for t in titles.values()), topic)
        bodies = ca.decisions(text)
        for d, phrases in {"D3": ("system of record", "CDC", "*Cutover", "*Rollback"),
                           "D4": ("outbox", "Where the outbox and audit tables live"),
                           "D7": ("Definition of moved", "LEGACY", "SHADOW", "RETIRED")}.items():
            for p in phrases:
                self.assertIn(p, bodies[d], f"{d}: {p}")
        components = json.loads(ca.read("docs/adr/0001-components.json"))["components"]
        kinds = {c["kind"] for c in components}
        self.assertTrue({"service", "batch", "data-store", "messaging"} <= kinds)

    def test_slug_matches_github(self):
        self.assertEqual(ca.slug("2. DB2 tables and CRUD matrix"), "2-db2-tables-and-crud-matrix")
        self.assertEqual(ca.slug("PUWR: UNDWRT (underwriting decision)"), "puwr-undwrt-underwriting-decision")
        self.assertEqual(ca.slug("Q-518-1"), "q-518-1")

    def test_anchor_in_same_file(self):
        text = "# T\n\n## Good section\n\n[ok](#good-section) [bad](#missing)\n"
        errors = ca.link_errors("docs/adr/x.md", text)
        self.assertEqual(errors, ["docs/adr/x.md: broken anchor #missing"])

    def test_anchor_in_other_file(self):
        ok = "[q](../modernization/quirk-register.md#q-518-1)"
        bad = "[q](../modernization/quirk-register.md#q-999)"
        self.assertEqual(ca.link_errors("docs/adr/x.md", ok), [])
        self.assertTrue(any("broken anchor" in e for e in ca.link_errors("docs/adr/x.md", bad)))

    def test_headings_in_code_blocks_are_not_anchors(self):
        self.assertNotIn("not-a-heading", ca.anchors("```\n# not a heading\n```\n"))
        self.assertIn("dup-1", ca.anchors("## Dup\n## Dup\n"))


class MarkdownAnchorTest(unittest.TestCase):
    def test_setext_headings(self):
        text = "Intro\n\nRelease notes\n-------------\n\nText\n\nOverview\n========\n"
        self.assertTrue({"release-notes", "overview"} <= ca.anchors(text))

    def test_setext_same_file_link(self):
        text = "Release notes\n-------------\n\n[details](#release-notes)\n"
        self.assertEqual(ca.link_errors("docs/adr/x.md", text), [])

    def test_table_separator_is_not_a_heading(self):
        self.assertNotIn("-a-b-", ca.anchors("| a | b |\n|---|---|\n"))

    def test_fenced_headings_ignored(self):
        for fence in ("```", "~~~"):
            self.assertNotIn("not-a-heading", ca.anchors(f"{fence}\n# Not a heading\n{fence}\n"))

    def test_links_in_fences_ignored(self):
        for fence in ("```", "~~~", "````"):
            text = f"{fence}md\n[consumer](#replace-with-section)\n[x](missing.md)\n{fence}\n"
            self.assertEqual(ca.link_errors("docs/adr/x.md", text), [])

    def test_broken_anchor_outside_fence_reported(self):
        errs = ca.link_errors("docs/adr/x.md", "# Title\n\n[x](#nope)\n")
        self.assertTrue(any("broken anchor" in e for e in errs))


if __name__ == "__main__":
    unittest.main()
