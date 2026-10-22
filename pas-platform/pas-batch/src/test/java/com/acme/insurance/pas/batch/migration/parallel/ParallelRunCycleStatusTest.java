package com.acme.insurance.pas.batch.migration.parallel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ParallelRunCycleStatusTest {
  @Test
  void cycleIsCleanOnlyWhenEveryDateRanAndNoUntriagedOrBugDifferencesRemain() {
    assertThat(ParallelRunService.cycleStatus(5, 5, 0)).isEqualTo("CLEAN");
    assertThat(ParallelRunService.cycleStatus(5, 4, 0)).isEqualTo("RUNNING");
    assertThat(ParallelRunService.cycleStatus(5, 5, 1)).isEqualTo("DIFFERENCES");
  }
}
