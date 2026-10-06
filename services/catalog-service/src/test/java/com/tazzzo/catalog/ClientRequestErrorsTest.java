package com.tazzzo.catalog;

import com.tazzzo.catalog.api.ApiExceptionHandler;
import com.tazzzo.catalog.api.ClientRequestErrors;
import com.tazzzo.catalog.api.ClientRequestErrors.Kind;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Error-handling hardening: the request-shape classification, and the structural guarantee that no
 * {@code @ExceptionHandler(Exception.class)} catch-all can turn a framework request-shape failure into a 500.
 */
class ClientRequestErrorsTest {

    @Test
    void request_shape_failures_are_classified_as_the_client_errors_they_are() throws Exception {
        assertThat(ClientRequestErrors.classify(new HttpMessageNotReadableException("x", new MockHttpInputMessage(new byte[0]))))
                .isEqualTo(Kind.MALFORMED);
        assertThat(ClientRequestErrors.classify(new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of())))
                .isEqualTo(Kind.UNSUPPORTED_MEDIA_TYPE);
        assertThat(ClientRequestErrors.classify(new HttpMediaTypeNotAcceptableException(List.of())))
                .isEqualTo(Kind.NOT_ACCEPTABLE);
        assertThat(ClientRequestErrors.classify(new MissingServletRequestParameterException("pin", "String")))
                .isEqualTo(Kind.MALFORMED);
        Method m = ClientRequestErrorsTest.class.getDeclaredMethod("header", String.class);
        assertThat(ClientRequestErrors.classify(new MissingRequestHeaderException("If-Match", new MethodParameter(m, 0))))
                .isEqualTo(Kind.MALFORMED);
        assertThat(ClientRequestErrors.classify(new TypeMismatchException("abc", Integer.class))).isEqualTo(Kind.MALFORMED);
    }

    @Test
    void kinds_carry_their_http_status_and_a_generic_message() {
        assertThat(Kind.MALFORMED.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(Kind.UNSUPPORTED_MEDIA_TYPE.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(Kind.NOT_ACCEPTABLE.status()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        for (Kind k : Kind.values()) {
            assertThat(k.message()).matches("[a-z ]+");
        }
    }

    /** Negative: genuine server faults stay unclassified, so the catch-alls still answer them as a logged 500. */
    @Test
    void server_faults_are_never_classified_as_client_errors() {
        assertThat(ClientRequestErrors.classify(new RuntimeException("boom"))).isNull();
        assertThat(ClientRequestErrors.classify(new IllegalStateException("corrupt"))).isNull();
        assertThat(ClientRequestErrors.classify(new IllegalArgumentException("bad"))).isNull();
        assertThat(ClientRequestErrors.classify(new NullPointerException())).isNull();
        assertThat(ClientRequestErrors.classify(new com.mongodb.MongoException("down"))).isNull();
        assertThat(ClientRequestErrors.classify(new HttpMessageNotWritableException("cannot write"))).isNull();
        // a method mismatch is decided before any controller is chosen, so a scoped advice never sees it
        assertThat(ClientRequestErrors.classify(new HttpRequestMethodNotSupportedException("PATCH"))).isNull();
        assertThat(ClientRequestErrors.classify(null)).isNull();
    }

    /**
     * Structural guard: every controller advice that ends in an {@code Exception}/{@code Throwable} catch-all must consult
     * {@link ClientRequestErrors}; the one global advice instead maps the three framework failures explicitly. A new
     * catch-all that forgets the seam fails here, before it can turn a malformed request into a 500.
     */
    @Test
    void every_catch_all_controller_advice_consults_the_classification() {
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.tazzzo");
        List<String> catchAlls = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (JavaClass c : classes) {
            if (!c.isAnnotatedWith(RestControllerAdvice.class) || !hasCatchAll(c.reflect())) {
                continue;
            }
            catchAlls.add(c.getSimpleName());
            if (c.isEquivalentTo(ApiExceptionHandler.class)) {
                continue;
            }
            boolean consults = c.getDirectDependenciesFromSelf().stream()
                    .anyMatch(d -> d.getTargetClass().isEquivalentTo(ClientRequestErrors.class));
            if (!consults) {
                missing.add(c.getName());
            }
        }
        assertThat(missing).as("catch-all advices that bypass ClientRequestErrors").isEmpty();
        // the 12 scoped catch-all advices plus the global one (StaffSupport has no catch-all: it falls through to it)
        assertThat(catchAlls).hasSize(13);
        assertThat(handledTypes(ApiExceptionHandler.class)).contains(HttpMessageNotReadableException.class,
                HttpMediaTypeNotSupportedException.class, HttpMediaTypeNotAcceptableException.class);
    }

    private static boolean hasCatchAll(Class<?> advice) {
        Set<Class<?>> broad = Set.of(Exception.class, Throwable.class, RuntimeException.class);
        return handledTypes(advice).stream().anyMatch(broad::contains);
    }

    private static List<Class<?>> handledTypes(Class<?> advice) {
        List<Class<?>> types = new ArrayList<>();
        for (Method method : advice.getDeclaredMethods()) {
            ExceptionHandler h = method.getAnnotation(ExceptionHandler.class);
            if (h == null) {
                continue;
            }
            if (h.value().length > 0) {
                types.addAll(Arrays.asList(h.value()));
            } else {
                Arrays.stream(method.getParameterTypes()).filter(Throwable.class::isAssignableFrom).forEach(types::add);
            }
        }
        return types;
    }

    @SuppressWarnings("unused")
    private void header(String ifMatch) { }
}
