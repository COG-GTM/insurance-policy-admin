package com.acme.insurance.pas.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesValidHeaderAndClearsMdc() throws Exception {
        MockHttpServletRequest request = request("incoming-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) ->
                        assertEquals("incoming-123", MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertEquals("incoming-123", response.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void generatesIdWhenHeaderIsAbsent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(null), response, new MockFilterChain());

        assertNotNull(response.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertEquals(36, response.getHeader(CorrelationIdFilter.HEADER_NAME).length());
    }

    @Test
    void replacesInvalidHeaders() throws Exception {
        for (String invalid : new String[] {"bad\r\nheader", "x".repeat(65)}) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request(invalid), response, new MockFilterChain());

            assertNotNull(response.getHeader(CorrelationIdFilter.HEADER_NAME));
            assertNotEquals(invalid, response.getHeader(CorrelationIdFilter.HEADER_NAME));
            assertEquals(36, response.getHeader(CorrelationIdFilter.HEADER_NAME).length());
        }
    }

    @Test
    void clearsMdcWhenChainThrows() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletException expected = new ServletException("chain failed");

        assertThrows(
                ServletException.class,
                () -> filter.doFilter(request("failing-request"), response, (servletRequest, servletResponse) -> {
                    assertEquals("failing-request", MDC.get(CorrelationIdFilter.MDC_KEY));
                    throw expected;
                }));

        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    private MockHttpServletRequest request(String correlationId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (correlationId != null) {
            request.addHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
        }
        return request;
    }
}
