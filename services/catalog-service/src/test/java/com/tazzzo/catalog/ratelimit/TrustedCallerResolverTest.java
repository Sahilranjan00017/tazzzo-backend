package com.tazzzo.catalog.ratelimit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The credential check: who is trusted, what is logged, and that the secret is never compared or printed unsafely. */
class TrustedCallerResolverTest {

    static final String SECRET = "sf-0123456789abcdef0123456789abcdef";          // 35 chars, fixture only
    static final String SAME_LENGTH_LAST_CHAR = "sf-0123456789abcdef0123456789abcdeX";
    static final String SAME_LENGTH_FIRST_CHAR = "Xf-0123456789abcdef0123456789abcdef";

    final AtomicLong clock = new AtomicLong(TimeUnit.HOURS.toNanos(1));
    ListAppender<ILoggingEvent> logs;
    Logger logger;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        logger = (Logger) LoggerFactory.getLogger(TrustedCallerResolver.class);
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private static TrustedCallerProperties.Caller caller(String name, String secret) {
        TrustedCallerProperties.Caller c = new TrustedCallerProperties.Caller();
        c.setName(name);
        c.setSecret(secret);
        return c;
    }

    private TrustedCallerResolver storefront() {
        return new TrustedCallerResolver(List.of(caller("storefront", SECRET)), clock::get);
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private String everythingLogged() {
        return String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    // ---------- who is trusted ----------

    @Test
    void the_configured_name_with_its_exact_secret_is_trusted() {
        assertThat(storefront().resolve("storefront", SECRET)).contains("storefront");
        assertThat(warnings()).isEmpty();
    }

    @Test
    void no_credential_at_all_is_the_ordinary_anonymous_request_and_is_not_logged() {
        TrustedCallerResolver r = storefront();
        assertThat(r.resolve(null, null)).isEmpty();
        assertThat(r.resolve("", "")).isEmpty();
        assertThat(r.resolve("   ", null)).isEmpty();
        assertThat(warnings()).isEmpty();
    }

    @Test
    void a_wrong_secret_is_untrusted_wherever_it_differs_and_whatever_its_length() {
        TrustedCallerResolver r = storefront();
        for (String wrong : List.of(SAME_LENGTH_LAST_CHAR, SAME_LENGTH_FIRST_CHAR, SECRET + "x",
                SECRET.substring(0, SECRET.length() - 1), "x", SECRET.toUpperCase(), " " + SECRET)) {
            assertThat(r.resolve("storefront", wrong)).as(wrong).isEmpty();
        }
    }

    @Test
    void a_secret_valid_for_one_caller_does_not_authenticate_another_name() {
        String other = "bo-0123456789abcdef0123456789abcdef";
        TrustedCallerResolver r = new TrustedCallerResolver(
                List.of(caller("storefront", SECRET), caller("backoffice", other)), clock::get);
        assertThat(r.resolve("backoffice", SECRET)).isEmpty();
        assertThat(r.resolve("storefront", other)).isEmpty();
        assertThat(r.resolve("backoffice", other)).contains("backoffice");
    }

    @Test
    void one_header_without_the_other_is_untrusted() {
        TrustedCallerResolver r = storefront();
        assertThat(r.resolve("storefront", null)).isEmpty();
        assertThat(r.resolve("storefront", "")).isEmpty();
        assertThat(r.resolve(null, SECRET)).isEmpty();
    }

    @Test
    void with_no_configuration_nothing_is_trusted_even_a_plausible_credential() {
        for (TrustedCallerResolver r : List.of(new TrustedCallerResolver(List.of(), clock::get),
                new TrustedCallerResolver(null, clock::get))) {
            assertThat(r.hasCallers()).isFalse();
            assertThat(r.resolve("storefront", SECRET)).isEmpty();
        }
        assertThat(warnings()).as("unknown names are logged as 'unknown', never echoed")
                .allSatisfy(w -> assertThat(w).contains("caller=unknown").doesNotContain("storefront"));
    }

    // ---------- what is logged ----------

    @Test
    void a_rejection_warns_with_the_caller_name_and_reason_and_never_the_secret() {
        TrustedCallerResolver r = storefront();
        r.resolve("storefront", SAME_LENGTH_LAST_CHAR);
        assertThat(warnings()).singleElement().asString()
                .contains("caller=storefront").contains("reason=wrong_secret");
        assertThat(everythingLogged()).doesNotContain(SECRET).doesNotContain(SAME_LENGTH_LAST_CHAR)
                .doesNotContain("0123456789abcdef");
    }

    @Test
    void an_unknown_presented_name_is_never_echoed_into_the_log() {
        TrustedCallerResolver r = storefront();
        r.resolve("evil\nINJECTED caller=storefront", SECRET);
        assertThat(warnings()).singleElement().asString()
                .contains("caller=unknown").contains("reason=unknown_name").doesNotContain("evil").doesNotContain("INJECTED");
        assertThat(everythingLogged()).doesNotContain(SECRET);
    }

    @Test
    void warnings_are_throttled_to_one_a_minute_per_caller_and_report_what_they_suppressed() {
        TrustedCallerResolver r = storefront();
        r.resolve("storefront", SAME_LENGTH_LAST_CHAR);
        r.resolve("storefront", SAME_LENGTH_FIRST_CHAR);
        r.resolve("storefront", null);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(59));
        r.resolve("storefront", SAME_LENGTH_LAST_CHAR);
        assertThat(warnings()).as("one WARN within the minute").hasSize(1);

        r.resolve("nobody", SECRET);
        assertThat(warnings()).as("the unknown-name window is separate").hasSize(2);

        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        r.resolve("storefront", SAME_LENGTH_LAST_CHAR);
        assertThat(warnings()).hasSize(3);
        assertThat(warnings().get(2)).contains("caller=storefront").contains("suppressed_since_last=3");
    }

    // ---------- zero-downtime rotation: two accepted secrets ----------

    static final String NEW_SECRET = "sf-new-fedcba9876543210fedcba9876543210";

    private static TrustedCallerProperties.Caller rotating(String name, String secret, String previous) {
        TrustedCallerProperties.Caller c = caller(name, secret);
        c.setPreviousSecret(previous);
        return c;
    }

    @Test
    void during_a_rotation_either_secret_admits_as_the_caller_and_a_third_does_not() {
        TrustedCallerResolver r = new TrustedCallerResolver(List.of(rotating("storefront", NEW_SECRET, SECRET)), clock::get);
        assertThat(r.resolve("storefront", NEW_SECRET)).as("the new (current) secret").contains("storefront");
        assertThat(r.resolve("storefront", SECRET)).as("the previous secret").contains("storefront");
        assertThat(r.resolve("storefront", SAME_LENGTH_LAST_CHAR)).as("a third secret").isEmpty();
        assertThat(r.resolve("storefront", NEW_SECRET + "x")).isEmpty();
        assertThat(warnings()).singleElement().asString().contains("reason=wrong_secret");
        assertThat(everythingLogged()).doesNotContain(SECRET).doesNotContain(NEW_SECRET);
    }

    @Test
    void an_empty_previous_secret_means_none_and_nothing_else_is_accepted() {
        for (String none : new String[]{null, ""}) {
            TrustedCallerResolver r = new TrustedCallerResolver(List.of(rotating("storefront", SECRET, none)), clock::get);
            assertThat(r.resolve("storefront", SECRET)).contains("storefront");
            assertThat(r.resolve("storefront", NEW_SECRET)).isEmpty();
        }
    }

    @Test
    void a_previous_secret_is_validated_like_the_secret_and_never_echoed() {
        String weak = "too-short-previous-secret";
        assertThatThrownBy(() -> new TrustedCallerResolver(List.of(rotating("storefront", SECRET, weak))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-callers[0].previous-secret").hasMessageNotContaining(weak)
                .hasMessageNotContaining(SECRET);
        String spaced = "sf 0123456789abcdef0123456789abcdef-prev";
        assertThatThrownBy(() -> new TrustedCallerResolver(List.of(rotating("storefront", SECRET, spaced))))
                .hasMessageContaining("previous-secret").hasMessageNotContaining(spaced);
        assertThatThrownBy(() -> new TrustedCallerResolver(List.of(rotating("storefront", SECRET, SECRET))))
                .as("a previous secret equal to the secret is a duplicate")
                .hasMessageContaining("previous-secret").hasMessageContaining("equals its secret")
                .hasMessageNotContaining(SECRET);
        assertThat(rotating("storefront", SECRET, NEW_SECRET).toString()).doesNotContain(SECRET).doesNotContain(NEW_SECRET);
    }

    // ---------- startup validation (never echoes the secret) ----------

    @Test
    void invalid_configuration_fails_at_startup_without_revealing_the_secret() {
        String shortSecret = "short-secret-value-31-chars-xyz";
        assertThat(shortSecret).hasSize(31);
        assertThatThrownBy(() -> new TrustedCallerResolver(List.of(caller("storefront", shortSecret))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-callers[0].secret").hasMessageNotContaining(shortSecret);
        assertThatThrownBy(() -> new TrustedCallerResolver(List.of(caller("storefront", null))))
                .hasMessageContaining("trusted-callers[0].secret");
        String withSpace = "sf 0123456789abcdef0123456789abcdef";
        assertThatThrownBy(() -> new TrustedCallerResolver(List.of(caller("storefront", withSpace))))
                .hasMessageNotContaining(withSpace);
        assertThatThrownBy(() -> new TrustedCallerResolver(List.of(caller("storefront", "é".repeat(40)))))
                .hasMessageContaining("printable ASCII");
        for (String bad : new String[]{null, "", "Storefront", "store-front", "web1", "a".repeat(21), "_x", "unknown"}) {
            assertThatThrownBy(() -> new TrustedCallerResolver(List.of(caller(bad, SECRET))))
                    .as(String.valueOf(bad)).hasMessageContaining("trusted-callers[0].name")
                    .hasMessageNotContaining(SECRET);
        }
        assertThatThrownBy(() -> new TrustedCallerResolver(List.of(caller("storefront", SECRET), caller("storefront", SECRET))))
                .hasMessageContaining("configured twice").hasMessageNotContaining(SECRET);
        assertThat(caller("storefront", SECRET).toString()).doesNotContain(SECRET);
    }

    @Test
    void startup_announces_configured_names_only() {
        storefront();
        assertThat(everythingLogged()).contains("[storefront]").doesNotContain(SECRET);
    }

    // ---------- constant-time comparison (structural guard) ----------

    /**
     * Timing cannot be asserted reliably in a unit test, so the guard is on the bytecode: the resolver must
     * compare through {@link java.security.MessageDigest#isEqual} (constant time for equal-length inputs, and
     * both sides are fixed-length SHA-256 digests) and must contain no early-exit comparison at all.
     */
    @Test
    void the_secret_is_compared_only_through_MessageDigest_isEqual() throws IOException {
        List<String> calls = new ArrayList<>();
        List<Integer> resolveOpcodes = new ArrayList<>();
        try (InputStream in = TrustedCallerResolver.class.getResourceAsStream("TrustedCallerResolver.class")) {
            new ClassReader(in).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    boolean resolve = "resolve".contentEquals(name);
                    return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String m, String d, boolean itf) {
                            calls.add(owner + "." + m);
                        }

                        @Override
                        public void visitInsn(int op) {
                            if (resolve) resolveOpcodes.add(op);
                        }
                    };
                }
            }, 0);
        }
        assertThat(calls).contains("java/security/MessageDigest.isEqual");
        assertThat(calls.stream().filter("java/security/MessageDigest.isEqual"::equals).count())
                .as("the current/previous comparisons plus the previous==current startup check").isEqualTo(3);
        assertThat(calls).doesNotContain("java/lang/String.equals", "java/lang/String.contentEquals",
                "java/lang/String.compareTo", "java/lang/String.equalsIgnoreCase", "java/util/Arrays.equals",
                "java/util/Objects.equals", "java/lang/Object.equals");
        // The two slot results are combined with the non-short-circuit boolean operators (IOR/IAND), so the
        // second comparison always runs; '||' / '&&' would compile to conditional jumps instead.
        assertThat(resolveOpcodes).contains(org.springframework.asm.Opcodes.IOR, org.springframework.asm.Opcodes.IAND);
    }

    @Test
    void resolution_result_is_the_configured_name_not_the_raw_header() {
        assertThat(storefront().resolve("  storefront  ", SECRET)).isEqualTo(Optional.of("storefront"));
    }
}
