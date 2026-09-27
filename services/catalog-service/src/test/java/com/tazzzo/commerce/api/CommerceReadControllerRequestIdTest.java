package com.tazzzo.commerce.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-10B final review #7 — {@code RequestIdFilter} is mandatory and always runs before this
 * controller, so its attribute is always populated in production. This pins the DEFENSIVE
 * behavior for the case it somehow is not: fail fast, never return the literal string
 * {@code "null"} as though it were a real request id.
 */
class CommerceReadControllerRequestIdTest {

    private static final String ATTR = com.tazzzo.catalog.api.RequestIdFilter.REQUEST_ID;

    @Test void a_populated_attribute_is_returned_as_is() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ATTR, "req_abc123");
        assertThat(CommerceReadController.requestId(request)).isEqualTo("req_abc123");
    }

    @Test void a_missing_attribute_fails_fast_rather_than_returning_the_literal_null() {
        MockHttpServletRequest request = new MockHttpServletRequest();   // attribute never set
        assertThatThrownBy(() -> CommerceReadController.requestId(request))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void a_blank_attribute_fails_fast() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ATTR, "   ");
        assertThatThrownBy(() -> CommerceReadController.requestId(request))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void a_non_string_attribute_fails_fast() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ATTR, 12345);
        assertThatThrownBy(() -> CommerceReadController.requestId(request))
                .isInstanceOf(IllegalStateException.class);
    }
}
