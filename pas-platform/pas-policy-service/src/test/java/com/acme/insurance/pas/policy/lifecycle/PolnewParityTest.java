package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.parity.ParityHarness;
import org.junit.jupiter.api.Test;

/**
 * POLNEW goldens vs the Java new-business rules. Q-27 (policy numbers) and Q-28 (dates, coverage
 * amounts) are the only accepted differences, listed in parity/known-differences.json.
 */
class PolnewParityTest {

  @Test
  void matchesCobolGoldenApartFromRegisteredFixes() {
    ParityHarness.assertParity(new PolnewParityAdapter());
  }
}
