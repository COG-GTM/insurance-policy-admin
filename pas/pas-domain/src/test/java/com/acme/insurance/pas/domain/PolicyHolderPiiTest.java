package com.acme.insurance.pas.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PolicyHolderPiiTest {

  private static final PolicyHolder HOLDER =
      new PolicyHolder(
          "CUST000042",
          "I",
          "Zzlastname",
          "Zzfirstname",
          "Q",
          "Zzcompany",
          "Zzaddress1",
          "Zzaddress2",
          "Zzcity",
          "IL",
          "99999",
          "USA",
          "555-0199",
          "zz@example.invalid",
          LocalDate.of(1961, 7, 13),
          "9876",
          "98-7654321",
          712,
          "S",
          "Y",
          LocalDate.of(2020, 1, 1),
          LocalDateTime.of(2024, 1, 1, 12, 0));

  @Test
  void restrictedFieldsAreTheOnesNamedInAb521() {
    Map<String, Pii.Level> levels =
        Arrays.stream(PolicyHolder.class.getRecordComponents())
            .filter(c -> c.isAnnotationPresent(Pii.class))
            .collect(
                Collectors.toMap(
                    RecordComponent::getName, c -> c.getAnnotation(Pii.class).value()));

    assertThat(levels)
        .containsEntry("dateOfBirth", Pii.Level.RESTRICTED)
        .containsEntry("ssnLast4", Pii.Level.RESTRICTED)
        .containsEntry("taxId", Pii.Level.RESTRICTED)
        .containsEntry("creditScore", Pii.Level.RESTRICTED)
        .containsEntry("email", Pii.Level.PERSONAL)
        .containsEntry("lastName", Pii.Level.PERSONAL);
  }

  @Test
  void toStringNeverContainsAnnotatedValues() throws ReflectiveOperationException {
    String text = HOLDER.toString();

    for (RecordComponent component : PolicyHolder.class.getRecordComponents()) {
      if (component.isAnnotationPresent(Pii.class)) {
        Object value = component.getAccessor().invoke(HOLDER);
        assertThat(text).as(component.getName()).doesNotContain(String.valueOf(value));
      }
    }
    assertThat(text).contains("CUST000042").contains(PolicyHolder.MASK);
  }

  @Test
  void constraintMessagesNameTheFieldNotTheValue() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new PolicyHolder(
                    "C1", null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, "123456", null, null, null, null, null, null))
        .hasMessageContaining("ssnLast4")
        .hasMessageNotContaining("123456");
  }
}
