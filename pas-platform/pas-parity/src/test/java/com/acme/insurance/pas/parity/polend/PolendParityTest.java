package com.acme.insurance.pas.parity.polend;

import com.acme.insurance.pas.parity.ParityHarness;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class PolendParityTest {
  @TestFactory
  Stream<DynamicTest> matchesCobolFixtures() {
    return ParityHarness.dynamicTests(new PolendParityAdapter());
  }
}
