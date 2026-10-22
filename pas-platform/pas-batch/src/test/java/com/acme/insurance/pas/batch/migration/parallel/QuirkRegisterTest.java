package com.acme.insurance.pas.batch.migration.parallel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class QuirkRegisterTest {
  @Test
  void loadsThePackagedQuirkRegisterAndRequiresFixDecisionForApprovedFix() {
    QuirkRegister register = QuirkRegister.load();

    assertThat(register.approvesFix("Q-01")).isTrue();
    assertThat(register.approvesFix("Q-03")).isTrue();
    assertThat(register.approvesFix("Q-04")).isFalse();
  }

  @Test
  void parsesOnlyDecisionRowsWithStableQuirkIds() throws Exception {
    QuirkRegister register =
        QuirkRegister.parse(
            new BufferedReader(
                new StringReader(
                    """
                    | Q-id | Area | Quirk | Owner | Decision | Ticket |
                    |------|------|-------|-------|----------|--------|
                    | Q-01 | Area | Test | Owner | Fix (flagged) | AB-1 |
                    | Q-02 | Area | Test | Owner | Preserve | AB-2 |
                    """)));

    assertThat(register.approvesFix("Q-01")).isTrue();
    assertThat(register.approvesFix("Q-02")).isFalse();
    assertThat(register.approvesFix("Q-03")).isFalse();
  }
}
