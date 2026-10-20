package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.parity.ParityHarness;
import org.junit.jupiter.api.Test;

/**
 * POLRNW / POLEND goldens vs the Java renewal and endorsement code. Intentional differences (Q-01,
 * Q-02, Q-09, Q-14) are listed in parity/known-differences.json.
 */
class PolrnwPolendParityTest {

  @Test
  void renewalMatchesCobolGoldenExceptApprovedFixes() {
    ParityHarness.assertParity(new PolrnwParityAdapter());
  }

  @Test
  void endorsementMatchesCobolGoldenExceptApprovedFixes() {
    ParityHarness.assertParity(new PolendParityAdapter());
  }
}
