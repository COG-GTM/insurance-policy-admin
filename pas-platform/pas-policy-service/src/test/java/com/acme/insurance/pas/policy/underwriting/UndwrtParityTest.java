package com.acme.insurance.pas.policy.underwriting;

import com.acme.insurance.pas.parity.ParityHarness;
import org.junit.jupiter.api.Test;

/** UNDWRT goldens (incl. every threshold ±1) vs the Java risk scorer. */
class UndwrtParityTest {

  @Test
  void matchesCobolGolden() {
    ParityHarness.assertParity(new UndwrtParityAdapter());
  }
}
