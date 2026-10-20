package com.acme.insurance.pas.rating;

import com.acme.insurance.pas.parity.ParityHarness;
import org.junit.jupiter.api.Test;

/** PREMBAT golden vs the rating component on the PARITY in-memory tables. */
class PrembatParityTest {

  @Test
  void matchesCobolGolden() {
    ParityHarness.assertParity(new PrembatParityAdapter(RatingFixtures.paritySnapshot()));
  }
}
