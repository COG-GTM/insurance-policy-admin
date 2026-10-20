package com.acme.insurance.pas.batch.placeholder;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.batch.repeat.RepeatStatus;

class NoopTaskletTest {

  @Test
  void taskletReturnsFinished() throws Exception {
    assertThat(new NoopJobConfig().noopTasklet().execute(null, null))
        .isEqualTo(RepeatStatus.FINISHED);
  }
}
