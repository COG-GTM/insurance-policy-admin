package com.acme.insurance.pas.policyservice.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  void keepsASafeIncomingIdAndExposesItInMdcAndResponse() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(CorrelationIdFilter.HEADER, "abc-123_XYZ.789");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seenInMdc = new AtomicReference<>();

    filter.doFilter(
        request,
        response,
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            seenInMdc.set(MDC.get(CorrelationIdFilter.MDC_KEY));
          }
        });

    assertThat(seenInMdc.get()).isEqualTo("abc-123_XYZ.789");
    assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123_XYZ.789");
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
  }

  @Test
  void replacesMissingOrUnsafeIdsWithAGeneratedOne() {
    assertThat(CorrelationIdFilter.resolve(null)).hasSize(36);
    assertThat(CorrelationIdFilter.resolve("short")).hasSize(36);
    assertThat(CorrelationIdFilter.resolve("bad\nvalue-with-newline")).hasSize(36);
    assertThat(CorrelationIdFilter.resolve("x".repeat(65))).hasSize(36);
  }
}
