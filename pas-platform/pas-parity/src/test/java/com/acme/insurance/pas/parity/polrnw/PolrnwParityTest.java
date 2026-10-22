package com.acme.insurance.pas.parity.polrnw;

import com.acme.insurance.pas.parity.ParityHarness;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class PolrnwParityTest {
  @TestFactory
  Stream<DynamicTest> matchesCobolFixtures() {
    return ParityHarness.dynamicTests(new PolrnwParityAdapter());
  }
}
