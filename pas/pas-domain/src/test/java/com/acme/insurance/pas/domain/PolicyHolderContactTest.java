package com.acme.insurance.pas.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PolicyHolderContactTest {

  @Test
  void toStringPrintsNoPersonalData() {
    PolicyHolderContact contact =
        new PolicyHolderContact(
            "CUST000042",
            "I",
            "Zzlastname",
            "Zzfirstname",
            "Zzcompany",
            "555-0199",
            "zz@example.invalid",
            false);

    assertThat(contact.toString())
        .contains("CUST000042")
        .doesNotContain("Zzlastname")
        .doesNotContain("Zzfirstname")
        .doesNotContain("Zzcompany")
        .doesNotContain("555-0199")
        .doesNotContain("zz@example.invalid");
  }
}
