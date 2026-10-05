package com.tazzzo.catalog.api;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The inbound correlation id is untrusted text: accepted only in a bounded, log-safe shape, otherwise dropped silently. */
class RequestIdFilterTest {

    private record Seen(String requestId, String correlationAttr, String correlationMdc) { }

    private Seen run(String header, MockHttpServletResponse res) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/health/live");
        if (header != null) {
            req.addHeader(RequestIdFilter.CORRELATION_HEADER, header);
        }
        AtomicReference<Seen> seen = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> seen.set(new Seen(String.valueOf(rq.getAttribute(RequestIdFilter.REQUEST_ID)),
                (String) rq.getAttribute(RequestIdFilter.CORRELATION_ID), MDC.get(RequestIdFilter.CORRELATION_ID)));
        new RequestIdFilter().doFilter(req, res, chain);
        return seen.get();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "trace-1234", "a.b:c_d-e", "0", "A111111111111111111111111111111111111111111111111111111111111111"})
    void a_well_formed_correlation_id_is_kept_in_mdc_and_echoed(String id) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        Seen seen = run(id, res);
        assertThat(seen.correlationAttr()).isEqualTo(id);
        assertThat(seen.correlationMdc()).isEqualTo(id);
        assertThat(res.getHeader(RequestIdFilter.CORRELATION_HEADER)).isEqualTo(id);
        assertThat(seen.requestId()).startsWith("req_").hasSize(24);
        assertThat(res.getHeader("X-Request-Id")).isEqualTo(seen.requestId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "-leading-dash", "has space", "semi;colon", "slash/x", "percent%0a", "quote\"x",
            "unicodeé", "A1111111111111111111111111111111111111111111111111111111111111111",
            "x\tTAB", "{\"json\":1}", "../../etc", "<script>"})
    void anything_else_is_dropped_and_never_echoed(String id) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        Seen seen = run(id, res);
        assertThat(seen.correlationAttr()).as(id).isNull();
        assertThat(seen.correlationMdc()).as(id).isNull();
        assertThat(res.getHeader(RequestIdFilter.CORRELATION_HEADER)).isNull();
        assertThat(seen.requestId()).startsWith("req_");
    }

    @Test
    void crlf_cannot_be_smuggled_through_the_shape() {
        assertThat(RequestIdFilter.acceptedCorrelationId("ok\r\nX-Injected: 1")).isNull();
        assertThat(RequestIdFilter.acceptedCorrelationId("ok\nmore")).isNull();
        assertThat(RequestIdFilter.acceptedCorrelationId("  trimmed-ok  ")).isEqualTo("trimmed-ok");
    }

    @Test
    void mdc_is_cleared_after_the_request() throws Exception {
        run("abc", new MockHttpServletResponse());
        assertThat(MDC.get(RequestIdFilter.REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestIdFilter.CORRELATION_ID)).isNull();
    }
}
