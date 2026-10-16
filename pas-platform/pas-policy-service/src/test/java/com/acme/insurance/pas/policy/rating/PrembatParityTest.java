package com.acme.insurance.pas.policy.rating;

import com.acme.insurance.pas.parity.ParityHarness;
import org.junit.jupiter.api.Test;

/** Runs the Java PremiumCalculator against the committed PREMBAT COBOL golden. */
class PrembatParityTest {

  @Test
  void matchesCobolGolden() {
    ParityHarness.assertParity(new PrembatParityAdapter());
  }
}
