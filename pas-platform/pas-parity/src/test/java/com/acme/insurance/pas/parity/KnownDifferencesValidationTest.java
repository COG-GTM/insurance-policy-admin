package com.acme.insurance.pas.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnownDifferencesValidationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void acceptsFixDecisionAndExistingFixture() throws Exception {
    prepareRoot("- **Decision:** **Fix.**");
    KnownDifferences knownDifferences =
        new KnownDifferences(
            temporaryDirectory,
            List.of(
                new KnownDifferences.Entry(
                    "UNDWRT",
                    "fixture",
                    "outputs.decision",
                    "Q-99",
                    "Explained difference.",
                    null)));

    assertThat(knownDifferences.validate()).isEmpty();
  }

  @Test
  void rejectsPreserveDecision() throws Exception {
    prepareRoot("- **Decision:** **Preserve.**");
    KnownDifferences knownDifferences =
        new KnownDifferences(
            temporaryDirectory,
            List.of(
                new KnownDifferences.Entry(
                    "UNDWRT",
                    "fixture",
                    "outputs.decision",
                    "Q-99",
                    "Explained difference.",
                    null)));

    assertThat(knownDifferences.validate())
        .contains("quirk must exist with a non-Preserve decision: Q-99");
  }

  @Test
  void rejectsUnknownQuirkId() throws Exception {
    prepareRoot("- **Decision:** **Fix.**");
    KnownDifferences knownDifferences =
        new KnownDifferences(
            temporaryDirectory,
            List.of(
                new KnownDifferences.Entry(
                    "UNDWRT",
                    "fixture",
                    "outputs.decision",
                    "Q-98",
                    "Explained difference.",
                    null)));

    assertThat(knownDifferences.validate())
        .contains("quirk must exist with a non-Preserve decision: Q-98");
  }

  @Test
  void rejectsDuplicateEntries() throws Exception {
    prepareRoot("- **Decision:** **Fix.**");
    KnownDifferences.Entry entry =
        new KnownDifferences.Entry(
            "UNDWRT", "fixture", "outputs.decision", "Q-99", "Explained difference.", null);
    KnownDifferences knownDifferences =
        new KnownDifferences(temporaryDirectory, List.of(entry, entry));

    assertThat(knownDifferences.validate())
        .contains("duplicate known difference: UNDWRT/fixture/outputs.decision");
  }

  private void prepareRoot(String decision) throws Exception {
    Path register = temporaryDirectory.resolve("docs/modernization/quirk-register.md");
    Files.createDirectories(register.getParent());
    Files.writeString(register, "### Q-99 Test\n\n" + decision + "\n");
    Path fixture = temporaryDirectory.resolve("parity/fixtures/UNDWRT/fixture.json");
    Files.createDirectories(fixture.getParent());
    Files.writeString(fixture, "{}\n");
  }
}
