package com.tazzzo.customer.profile;

import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-12A — {@code CustomerProfileService}/{@code CustomerProfileRepository} exercised directly
 * (no HTTP, no auth) over a real Mongo (Testcontainers). No sleeps: concurrency is pinned with
 * latches, time with a mutable {@link Clock} — the SAME conventions {@code CustomerSessionServiceIT}
 * established. {@code customerId} here is a plain fixture string; this layer trusts its caller
 * completely (the controller is the ONLY place a real {@code CustomerPrincipal} is required).
 */
@SpringBootTest(classes = {CatalogApplication.class, CustomerProfileServiceIT.TestBeans.class})
class CustomerProfileServiceIT extends AbstractMongoIT {

    static final AtomicReference<Instant> CLOCK_NOW = new AtomicReference<>(Instant.parse("2026-06-01T00:00:00Z"));

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        Clock mutableClock() {
            return new Clock() {
                @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public Instant instant() { return CLOCK_NOW.get(); }
            };
        }
    }

    @Autowired CustomerProfileService service;
    @Autowired CustomerProfileRepository repository;
    @Autowired Clock clock;

    @BeforeEach
    void resetClock() {
        CLOCK_NOW.set(Instant.parse("2026-06-01T00:00:00Z"));
    }

    private static String uniqueCustomerId(String suffix) {
        return "CUS_profileit" + suffix;
    }

    private Document rawDoc(String customerId) {
        return repository.findById(customerId);
    }

    // ---------- A: absent profile default ----------

    @Test void a_brand_new_customer_has_a_default_profile_at_version_zero() {
        String customerId = uniqueCustomerId("0001");
        CustomerProfileService.ProfileView view = service.get(customerId);
        assertThat(view.customerId()).isEqualTo(customerId);
        assertThat(view.displayName()).isNull();
        assertThat(view.email()).isNull();
        assertThat(view.version()).isZero();
        assertThat(rawDoc(customerId)).as("GET must never persist a document").isNull();
    }

    // ---------- D/E: create then read back ----------

    @Test void d_e_patch_at_version_zero_creates_version_one_and_get_reflects_it() {
        String customerId = uniqueCustomerId("0002");
        CustomerProfileService.ProfileView created =
                service.patch(customerId, 0, PatchField.of("Sahil Ranjan"), PatchField.absent());
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.displayName()).isEqualTo("Sahil Ranjan");
        assertThat(created.email()).isNull();

        CustomerProfileService.ProfileView fetched = service.get(customerId);
        assertThat(fetched.version()).isEqualTo(1);
        assertThat(fetched.displayName()).isEqualTo("Sahil Ranjan");
    }

    // ---------- F/G: partial patch leaves the other field untouched ----------

    @Test void f_partial_patch_of_display_name_only_leaves_email_unchanged() {
        String customerId = uniqueCustomerId("0003");
        service.patch(customerId, 0, PatchField.absent(), PatchField.of("a@example.com"));
        CustomerProfileService.ProfileView updated =
                service.patch(customerId, 1, PatchField.of("Megha Namdeo"), PatchField.absent());
        assertThat(updated.displayName()).isEqualTo("Megha Namdeo");
        assertThat(updated.email()).isEqualTo("a@example.com");
    }

    @Test void g_partial_patch_of_email_only_leaves_display_name_unchanged() {
        String customerId = uniqueCustomerId("0004");
        service.patch(customerId, 0, PatchField.of("José"), PatchField.absent());
        CustomerProfileService.ProfileView updated =
                service.patch(customerId, 1, PatchField.absent(), PatchField.of("Jose@Example.com"));
        assertThat(updated.displayName()).isEqualTo("José");
        assertThat(updated.email()).isEqualTo("jose@example.com"); // canonical lower-case
    }

    // ---------- H/I: explicit null clears ----------

    @Test void h_explicit_null_clears_display_name() {
        String customerId = uniqueCustomerId("0005");
        service.patch(customerId, 0, PatchField.of("李明"), PatchField.of("li@example.com"));
        CustomerProfileService.ProfileView cleared =
                service.patch(customerId, 1, PatchField.of(null), PatchField.absent());
        assertThat(cleared.displayName()).isNull();
        assertThat(cleared.email()).isEqualTo("li@example.com");
    }

    @Test void i_explicit_null_clears_email() {
        String customerId = uniqueCustomerId("0006");
        service.patch(customerId, 0, PatchField.of("Name"), PatchField.of("name@example.com"));
        CustomerProfileService.ProfileView cleared =
                service.patch(customerId, 1, PatchField.absent(), PatchField.of(null));
        assertThat(cleared.email()).isNull();
        assertThat(cleared.displayName()).isEqualTo("Name");
    }

    // ---------- K/L: invalid field values ----------

    @Test void k_display_name_over_80_code_points_is_rejected() {
        String customerId = uniqueCustomerId("0007");
        String tooLong = "x".repeat(81);
        assertThatThrownBy(() -> service.patch(customerId, 0, PatchField.of(tooLong), PatchField.absent()))
                .isInstanceOf(CustomerProfileFailure.class)
                .satisfies(e -> assertThat(((CustomerProfileFailure) e).reason())
                        .isEqualTo(CustomerProfileFailure.Reason.INVALID_REQUEST));
        assertThat(rawDoc(customerId)).as("a rejected patch must not create a document").isNull();
    }

    @Test void k_display_name_with_a_control_character_is_rejected() {
        String customerId = uniqueCustomerId("0008");
        String withControlChar = "Sahil" + '\u0007' + "Ranjan";
        assertThatThrownBy(() -> service.patch(customerId, 0, PatchField.of(withControlChar), PatchField.absent()))
                .isInstanceOf(CustomerProfileFailure.class)
                .satisfies(e -> assertThat(((CustomerProfileFailure) e).reason())
                        .isEqualTo(CustomerProfileFailure.Reason.INVALID_REQUEST));
    }

    @Test void l_malformed_email_is_rejected() {
        String customerId = uniqueCustomerId("0009");
        assertThatThrownBy(() -> service.patch(customerId, 0, PatchField.absent(), PatchField.of("not-an-email")))
                .isInstanceOf(CustomerProfileFailure.class)
                .satisfies(e -> assertThat(((CustomerProfileFailure) e).reason())
                        .isEqualTo(CustomerProfileFailure.Reason.INVALID_REQUEST));
    }

    @Test void l_email_with_internal_whitespace_is_rejected() {
        String customerId = uniqueCustomerId("0010");
        assertThatThrownBy(() -> service.patch(customerId, 0, PatchField.absent(), PatchField.of("a b@example.com")))
                .isInstanceOf(CustomerProfileFailure.class);
    }

    // ---------- M: stale If-Match / precondition failed ----------

    @Test void m_stale_expected_version_is_rejected_and_state_is_unchanged() {
        String customerId = uniqueCustomerId("0011");
        service.patch(customerId, 0, PatchField.of("Original"), PatchField.absent());
        assertThatThrownBy(() -> service.patch(customerId, 0, PatchField.of("Attacker"), PatchField.absent()))
                .isInstanceOf(CustomerProfileFailure.class)
                .satisfies(e -> assertThat(((CustomerProfileFailure) e).reason())
                        .isEqualTo(CustomerProfileFailure.Reason.PRECONDITION_FAILED));
        assertThat(service.get(customerId).displayName()).isEqualTo("Original");
    }

    // ---------- T: clock discipline ----------

    @Test void t_created_at_and_updated_at_come_from_the_injected_clock() {
        String customerId = uniqueCustomerId("0012");
        Instant createInstant = Instant.parse("2026-07-01T10:00:00Z");
        CLOCK_NOW.set(createInstant);
        service.patch(customerId, 0, PatchField.of("Name"), PatchField.absent());
        Document doc = rawDoc(customerId);
        assertThat(doc.getDate("createdAt").toInstant()).isEqualTo(createInstant);
        assertThat(doc.getDate("updatedAt").toInstant()).isEqualTo(createInstant);

        Instant updateInstant = Instant.parse("2026-08-01T11:00:00Z");
        CLOCK_NOW.set(updateInstant);
        service.patch(customerId, 1, PatchField.absent(), PatchField.of("clock@example.com"));
        Document updated = rawDoc(customerId);
        assertThat(updated.getDate("createdAt").toInstant())
                .as("createdAt never changes on update").isEqualTo(createInstant);
        assertThat(updated.getDate("updatedAt").toInstant()).isEqualTo(updateInstant);
    }

    // ---------- N: concurrent update at the same version -- exactly one winner ----------

    @Test void n_two_concurrent_patches_at_the_same_version_yield_exactly_one_winner() throws Exception {
        String customerId = uniqueCustomerId("0013");
        service.patch(customerId, 0, PatchField.of("Original"), PatchField.absent());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Object> results = new CopyOnWriteArrayList<>();
        try {
            for (String candidate : List.of("Alice", "Bob")) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        results.add(service.patch(customerId, 1, PatchField.of(candidate), PatchField.absent()));
                    } catch (CustomerProfileFailure e) {
                        results.add(e);
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        long successes = results.stream().filter(r -> r instanceof CustomerProfileService.ProfileView).count();
        long failures = results.stream().filter(r -> r instanceof CustomerProfileFailure f
                && f.reason() == CustomerProfileFailure.Reason.PRECONDITION_FAILED).count();
        assertThat(successes).as("exactly one winner").isEqualTo(1);
        assertThat(failures).as("exactly one loser, as a 412").isEqualTo(1);

        CustomerProfileService.ProfileView finalState = service.get(customerId);
        assertThat(finalState.version()).isEqualTo(2);
        assertThat(finalState.displayName()).isIn("Alice", "Bob");
    }

    // ---------- O: concurrent first-create -- exactly one winner, only one document ----------

    @Test void o_two_concurrent_first_creates_yield_exactly_one_winner_and_one_document() throws Exception {
        String customerId = uniqueCustomerId("0014");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Object> results = new CopyOnWriteArrayList<>();
        try {
            for (String candidate : List.of("First", "Second")) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        results.add(service.patch(customerId, 0, PatchField.of(candidate), PatchField.absent()));
                    } catch (CustomerProfileFailure e) {
                        results.add(e);
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        long successes = results.stream().filter(r -> r instanceof CustomerProfileService.ProfileView).count();
        long failures = results.stream().filter(r -> r instanceof CustomerProfileFailure f
                && f.reason() == CustomerProfileFailure.Reason.PRECONDITION_FAILED).count();
        assertThat(successes).as("exactly one create winner").isEqualTo(1);
        assertThat(failures).as("exactly one loser, as a 412").isEqualTo(1);
        assertThat(service.get(customerId).version()).isEqualTo(1);
    }
}
