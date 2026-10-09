package com.tazzzo.catalog;

import com.tazzzo.catalog.api.AcceptNegotiationInterceptor;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The up-front {@code Accept} decision: which handlers it covers, which {@code Accept} values pass (exactly those
 * Jackson can satisfy), and the structural guarantee that every controller advice writes its error body with a fixed
 * JSON {@code Content-Type}, so no error can fail a second time on negotiation.
 */
class AcceptNegotiationInterceptorTest {

    final AcceptNegotiationInterceptor interceptor = new AcceptNegotiationInterceptor(ContentNegotiationManager::new);

    /** Stand-in for an application controller (it lives in com.tazzzo, like every real one). */
    static class Handlers {
        public Map<String, String> body() { return Map.of(); }
        public ResponseEntity<Map<String, String>> entity() { return ResponseEntity.ok(Map.of()); }
        public ResponseEntity<Void> noBody() { return ResponseEntity.noContent().build(); }
        public void nothing() { }
    }

    HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Handlers(), Handlers.class.getMethod(name));
    }

    boolean accepts(String accept, String handler) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/customer/addresses");
        if (accept != null) req.addHeader("Accept", accept);
        return interceptor.preHandle(req, new MockHttpServletResponse(), handler(handler));
    }

    @Test
    void every_accept_a_json_client_may_send_passes() throws Exception {
        for (String accept : new String[]{null, "*/*", "application/json", "application/*", "application/json;charset=UTF-8",
                "application/problem+json", "application/vnd.tazzzo+json", "text/html, application/json;q=0.9",
                "application/xml, */*;q=0.1"}) {
            assertThat(accepts(accept, "body")).as(String.valueOf(accept)).isTrue();
            assertThat(accepts(accept, "entity")).as(String.valueOf(accept)).isTrue();
        }
    }

    @Test
    void an_accept_json_cannot_satisfy_is_refused_before_the_handler() {
        for (String accept : new String[]{"application/xml", "text/html", "text/plain", "image/png", "text/*",
                "application/xml, text/html"}) {
            assertThatThrownBy(() -> accepts(accept, "body")).as(accept)
                    .isInstanceOf(HttpMediaTypeNotAcceptableException.class);
            assertThatThrownBy(() -> accepts(accept, "entity")).as(accept)
                    .isInstanceOf(HttpMediaTypeNotAcceptableException.class);
        }
    }

    /** Nothing to negotiate: a handler with no body behaves exactly as before, whatever the Accept. */
    @Test
    void handlers_without_a_body_and_non_handler_methods_are_not_checked() throws Exception {
        assertThat(accepts("application/xml", "noBody")).isTrue();
        assertThat(accepts("application/xml", "nothing")).isTrue();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
        req.addHeader("Accept", "application/xml");
        assertThat(interceptor.preHandle(req, new MockHttpServletResponse(), new Object())).isTrue();
    }

    /** Framework handlers (springdoc, actuator, error pages) are outside com.tazzzo and keep their own negotiation. */
    @Test
    void framework_handlers_are_not_checked() throws Exception {
        HandlerMethod foreign = new HandlerMethod(new java.util.ArrayList<String>(), java.util.ArrayList.class.getMethod("toString"));
        assertThat(AcceptNegotiationInterceptor.class.getName()).startsWith("com.tazzzo.");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v3/api-docs");
        req.addHeader("Accept", "application/xml");
        assertThat(interceptor.preHandle(req, new MockHttpServletResponse(), foreign)).isTrue();
    }

    /**
     * Structural guard: in every controller advice, every {@code ResponseEntity} it starts also has its
     * {@code Content-Type} set, so an error body is never negotiated against {@code Accept} (a second negotiation failure
     * is what turned typed errors into 500s with an empty body and a stack trace).
     */
    @Test
    void every_controller_advice_fixes_the_content_type_of_every_error_response() {
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.tazzzo");
        Set<String> starters = Set.of("status", "ok", "badRequest", "notFound", "unprocessableEntity", "internalServerError",
                "accepted", "created", "noContent");
        List<String> violations = new ArrayList<>();
        int advices = 0;
        for (JavaClass c : classes) {
            if (!c.isAnnotatedWith(RestControllerAdvice.class)) {
                continue;
            }
            advices++;
            long started = 0;
            long typed = 0;
            for (JavaMethodCall call : c.getMethodCallsFromSelf()) {
                String owner = call.getTargetOwner().getName();
                String name = call.getName();
                if (owner.equals(ResponseEntity.class.getName()) && starters.contains(name)) {
                    started++;
                }
                if (owner.startsWith(ResponseEntity.class.getName() + "$") && name.equals("contentType")) {
                    typed++;
                }
            }
            if (started != typed) {
                violations.add(c.getName() + " starts " + started + " responses but types " + typed);
            }
        }
        assertThat(advices).as("every controller advice was inspected").isGreaterThanOrEqualTo(23);
        assertThat(violations).isEmpty();
    }
}
