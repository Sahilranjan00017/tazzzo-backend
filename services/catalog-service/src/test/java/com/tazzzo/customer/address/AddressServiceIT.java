package com.tazzzo.customer.address;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.auth.session.CustomerIdentityAuthorityImpl;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.Tx;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-12B — {@code AddressService}/{@code AddressRepository}/{@code CustomerAddressStateRepository}
 * exercised directly (no HTTP, no auth) over a real Mongo (Testcontainers). No sleeps: concurrency
 * is pinned with latches, time with a mutable {@link Clock} — the SAME conventions
 * {@code CustomerProfileServiceIT} established. {@code customerId} fixtures are synthetic and never
 * exist in the auth-owned {@code customers} collection, so the identity check is stubbed to always
 * succeed here; the REAL identity-integrity behavior is covered by
 * {@code AddressIdentityIntegrityHttpIT}.
 */
@SpringBootTest(classes = {CatalogApplication.class, AddressServiceIT.TestBeans.class})
class AddressServiceIT extends AbstractMongoIT {

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

        @Bean
        @Primary
        CustomerIdentityAuthority alwaysExistsAuthority() {
            return new CustomerIdentityAuthority() {
                @Override public boolean exists(CustomerId customerId) { return true; }
                @Override public boolean exists(ClientSession session, CustomerId customerId) { return true; }
            };
        }

        @Bean
        @Primary
        AddressLimitProperties smallLimit() {
            AddressLimitProperties p = new AddressLimitProperties();
            p.setMaxActiveAddresses(3); // small, so limit tests don't need 10 real inserts
            return p;
        }
    }

    @Autowired AddressService service;
    @Autowired AddressRepository addressRepo;
    @Autowired CustomerAddressStateRepository stateRepo;
    @Autowired Clock clock;
    @Autowired Tx tx;
    @Autowired AddressObservability observability;
    @Autowired ObjectProvider<CustomerIdentityAuthority> identityAuthorityProvider;
    @Autowired CustomerIdentityAuthorityImpl realIdentityAuthority;
    @Autowired AddressLimitProperties limits;

    @BeforeEach
    void resetClock() {
        CLOCK_NOW.set(Instant.parse("2026-06-01T00:00:00Z"));
    }

    private static CustomerId uniqueCustomerId(String suffix) {
        return new CustomerId("CUS_addressit" + suffix);
    }

    private static AddressService.CreateCommand create(String label, String name) {
        return new AddressService.CreateCommand(label, name, "+919876500001", "12 MG Road", null, null,
                "Bengaluru", "Karnataka", "560047", null, null);
    }

    private static AddressService.CreateCommand createWithCoords(String label, Double lat, Double lng) {
        return new AddressService.CreateCommand(label, "Name", "+919876500001", "12 MG Road", null, null,
                "Bengaluru", "Karnataka", "560047", lat, lng);
    }

    private static AddressService.PatchCommand emptyPatch() {
        return new AddressService.PatchCommand(PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent());
    }

    // ---------- 2/3: first/second create, default invariant ----------

    @Test void first_create_is_default_at_version_one() {
        CustomerId customerId = uniqueCustomerId("0001");
        AddressService.AddressView view = service.create(customerId, create("HOME", "Sahil"));
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.isDefault()).isTrue();
        assertThat(view.label()).isEqualTo(AddressLabel.HOME);
    }

    @Test void second_create_is_not_default() {
        CustomerId customerId = uniqueCustomerId("0002");
        service.create(customerId, create("HOME", "Sahil"));
        AddressService.AddressView second = service.create(customerId, create("WORK", "Sahil"));
        assertThat(second.isDefault()).isFalse();
    }

    // ---------- 4/5: list/get ----------

    @Test void list_returns_default_first_then_other_addresses() {
        CustomerId customerId = uniqueCustomerId("0003");
        service.create(customerId, create("HOME", "A"));
        service.create(customerId, create("WORK", "B"));
        List<AddressService.AddressView> views = service.list(customerId);
        assertThat(views).hasSize(2);
        assertThat(views.get(0).isDefault()).isTrue();
        assertThat(views.get(1).isDefault()).isFalse();
    }

    @Test void get_returns_the_saved_address() {
        CustomerId customerId = uniqueCustomerId("0004");
        AddressService.AddressView created = service.create(customerId, create("HOME", "Sahil"));
        AddressService.AddressView fetched = service.get(customerId, new AddressId(created.addressId()));
        assertThat(fetched.recipientName()).isEqualTo("Sahil");
    }

    @Test void get_unknown_address_is_not_found() {
        CustomerId customerId = uniqueCustomerId("0005");
        assertThatThrownBy(() -> service.get(customerId, AddressId.generate()))
                .isInstanceOf(AddressFailure.class)
                .satisfies(e -> assertThat(((AddressFailure) e).reason()).isEqualTo(AddressFailure.Reason.NOT_FOUND));
    }

    // ---------- 10/11/12/13: field validation ----------

    @Test void invalid_postal_code_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0006");
        AddressService.CreateCommand cmd = new AddressService.CreateCommand("HOME", "Name", "+919876500001",
                "Line1", null, null, "City", "State", "12345", null, null); // 5 digits
        assertThatThrownBy(() -> service.create(customerId, cmd))
                .isInstanceOf(AddressFailure.class)
                .satisfies(e -> assertThat(((AddressFailure) e).reason())
                        .isEqualTo(AddressFailure.Reason.INVALID_REQUEST));
    }

    @Test void only_latitude_without_longitude_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0007");
        assertThatThrownBy(() -> service.create(customerId, createWithCoords("HOME", 12.9, null)))
                .isInstanceOf(AddressFailure.class);
    }

    @Test void latitude_out_of_range_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0008");
        assertThatThrownBy(() -> service.create(customerId, createWithCoords("HOME", 91.0, 77.0)))
                .isInstanceOf(AddressFailure.class);
    }

    @Test void longitude_out_of_range_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0009");
        assertThatThrownBy(() -> service.create(customerId, createWithCoords("HOME", 12.9, 181.0)))
                .isInstanceOf(AddressFailure.class);
    }

    @Test void valid_coordinate_pair_is_accepted_and_persisted() {
        CustomerId customerId = uniqueCustomerId("0010");
        AddressService.AddressView view = service.create(customerId, createWithCoords("HOME", 12.9, 77.6));
        assertThat(view.latitude()).isEqualTo(12.9);
        assertThat(view.longitude()).isEqualTo(77.6);
    }

    @Test void both_coordinates_absent_is_accepted() {
        CustomerId customerId = uniqueCustomerId("0011");
        AddressService.AddressView view = service.create(customerId, create("HOME", "Name"));
        assertThat(view.latitude()).isNull();
        assertThat(view.longitude()).isNull();
    }

    @Test void invalid_recipient_phone_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0012");
        AddressService.CreateCommand cmd = new AddressService.CreateCommand("HOME", "Name", "12345",
                "Line1", null, null, "City", "State", "560047", null, null);
        assertThatThrownBy(() -> service.create(customerId, cmd)).isInstanceOf(AddressFailure.class);
    }

    @Test void blank_required_text_field_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0013");
        AddressService.CreateCommand cmd = new AddressService.CreateCommand("HOME", "  ", "+919876500001",
                "Line1", null, null, "City", "State", "560047", null, null);
        assertThatThrownBy(() -> service.create(customerId, cmd)).isInstanceOf(AddressFailure.class);
    }

    @Test void invalid_label_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0014");
        assertThatThrownBy(() -> service.create(customerId, create("NOWHERE", "Name")))
                .isInstanceOf(AddressFailure.class);
    }

    // ---------- 14/15/16/17: address limit + concurrency ----------

    @Test void address_limit_is_enforced() {
        CustomerId customerId = uniqueCustomerId("0015");
        service.create(customerId, create("HOME", "1"));
        service.create(customerId, create("HOME", "2"));
        service.create(customerId, create("HOME", "3")); // limit == 3 in this test class
        assertThatThrownBy(() -> service.create(customerId, create("HOME", "4")))
                .isInstanceOf(AddressFailure.class)
                .satisfies(e -> assertThat(((AddressFailure) e).reason())
                        .isEqualTo(AddressFailure.Reason.ADDRESS_LIMIT_REACHED));
    }

    @Test void two_concurrent_creates_at_the_limit_yield_exactly_one_winner() throws Exception {
        CustomerId customerId = uniqueCustomerId("0016");
        service.create(customerId, create("HOME", "1"));
        service.create(customerId, create("HOME", "2")); // now at limit-1 (2 of 3)

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Object> results = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        results.add(service.create(customerId, create("WORK", "Racer")));
                    } catch (AddressFailure e) {
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

        long successes = results.stream().filter(r -> r instanceof AddressService.AddressView).count();
        long limitFailures = results.stream().filter(r -> r instanceof AddressFailure f
                && f.reason() == AddressFailure.Reason.ADDRESS_LIMIT_REACHED).count();
        assertThat(successes).as("exactly one winner").isEqualTo(1);
        assertThat(limitFailures).as("exactly one loser, as ADDRESS_LIMIT_REACHED").isEqualTo(1);
        assertThat(service.list(customerId)).hasSize(3);
    }

    // ---------- 16/25/26/27: PATCH ----------

    @Test void patch_partial_update_of_recipient_name_only() {
        CustomerId customerId = uniqueCustomerId("0017");
        AddressService.AddressView created = service.create(customerId, create("HOME", "Original"));
        AddressService.PatchCommand cmd = new AddressService.PatchCommand(PatchField.absent(),
                PatchField.of("Updated"), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent());
        AddressService.AddressView updated = service.patch(customerId, new AddressId(created.addressId()), 1, cmd);
        assertThat(updated.recipientName()).isEqualTo("Updated");
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.city()).isEqualTo("Bengaluru"); // untouched
    }

    @Test void patch_explicit_null_clears_optional_landmark() {
        CustomerId customerId = uniqueCustomerId("0018");
        AddressService.CreateCommand cmd = new AddressService.CreateCommand("HOME", "Name", "+919876500001",
                "Line1", null, "Near the mall", "City", "State", "560047", null, null);
        AddressService.AddressView created = service.create(customerId, cmd);
        AddressService.PatchCommand patch = new AddressService.PatchCommand(PatchField.absent(),
                PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.of(null), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent());
        AddressService.AddressView updated = service.patch(customerId, new AddressId(created.addressId()), 1, patch);
        assertThat(updated.landmark()).isNull();
    }

    @Test void patch_required_field_explicit_null_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0019");
        AddressService.AddressView created = service.create(customerId, create("HOME", "Name"));
        AddressService.PatchCommand patch = new AddressService.PatchCommand(PatchField.absent(),
                PatchField.of(null), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent());
        assertThatThrownBy(() -> service.patch(customerId, new AddressId(created.addressId()), 1, patch))
                .isInstanceOf(AddressFailure.class)
                .satisfies(e -> assertThat(((AddressFailure) e).reason())
                        .isEqualTo(AddressFailure.Reason.INVALID_REQUEST));
    }

    @Test void patch_only_one_of_lat_lng_pair_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0020");
        AddressService.AddressView created = service.create(customerId, create("HOME", "Name"));
        AddressService.PatchCommand patch = new AddressService.PatchCommand(PatchField.absent(),
                PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.of(12.9), PatchField.absent());
        assertThatThrownBy(() -> service.patch(customerId, new AddressId(created.addressId()), 1, patch))
                .isInstanceOf(AddressFailure.class);
    }

    @Test void empty_patch_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0021");
        AddressService.AddressView created = service.create(customerId, create("HOME", "Name"));
        assertThatThrownBy(() -> service.patch(customerId, new AddressId(created.addressId()), 1, emptyPatch()))
                .isInstanceOf(AddressFailure.class)
                .satisfies(e -> assertThat(((AddressFailure) e).reason())
                        .isEqualTo(AddressFailure.Reason.INVALID_REQUEST));
    }

    @Test void stale_patch_is_rejected_and_state_unchanged() {
        CustomerId customerId = uniqueCustomerId("0022");
        AddressService.AddressView created = service.create(customerId, create("HOME", "Original"));
        AddressService.PatchCommand cmd = new AddressService.PatchCommand(PatchField.absent(),
                PatchField.of("Attacker"), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent());
        assertThatThrownBy(() -> service.patch(customerId, new AddressId(created.addressId()), 99, cmd))
                .isInstanceOf(AddressFailure.class)
                .satisfies(e -> assertThat(((AddressFailure) e).reason())
                        .isEqualTo(AddressFailure.Reason.PRECONDITION_FAILED));
        assertThat(service.get(customerId, new AddressId(created.addressId())).recipientName())
                .isEqualTo("Original");
    }

    // ---------- 22/23/24: DELETE + default replacement ----------

    @Test void delete_removes_the_address() {
        CustomerId customerId = uniqueCustomerId("0023");
        AddressService.AddressView created = service.create(customerId, create("HOME", "Name"));
        service.delete(customerId, new AddressId(created.addressId()), 1);
        assertThatThrownBy(() -> service.get(customerId, new AddressId(created.addressId())))
                .isInstanceOf(AddressFailure.class)
                .satisfies(e -> assertThat(((AddressFailure) e).reason()).isEqualTo(AddressFailure.Reason.NOT_FOUND));
    }

    @Test void stale_delete_is_rejected() {
        CustomerId customerId = uniqueCustomerId("0024");
        AddressService.AddressView created = service.create(customerId, create("HOME", "Name"));
        assertThatThrownBy(() -> service.delete(customerId, new AddressId(created.addressId()), 99))
                .isInstanceOf(AddressFailure.class)
                .satisfies(e -> assertThat(((AddressFailure) e).reason())
                        .isEqualTo(AddressFailure.Reason.PRECONDITION_FAILED));
        assertThat(service.get(customerId, new AddressId(created.addressId()))).isNotNull();
    }

    @Test void deleting_default_promotes_most_recently_updated_remaining_address() {
        CustomerId customerId = uniqueCustomerId("0025");
        AddressService.AddressView first = service.create(customerId, create("HOME", "First"));
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(10));
        AddressService.AddressView second = service.create(customerId, create("WORK", "Second"));
        // first is default; touch "second" so it is the most-recently-updated remaining address.
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(10));
        AddressService.PatchCommand touch = new AddressService.PatchCommand(PatchField.absent(),
                PatchField.of("Second Touched"), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent());
        service.patch(customerId, new AddressId(second.addressId()), 1, touch);

        service.delete(customerId, new AddressId(first.addressId()), 1);

        List<AddressService.AddressView> remaining = service.list(customerId);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).addressId()).isEqualTo(second.addressId());
        assertThat(remaining.get(0).isDefault()).isTrue();
    }

    @Test void deleting_the_last_address_leaves_zero_default() {
        CustomerId customerId = uniqueCustomerId("0026");
        AddressService.AddressView only = service.create(customerId, create("HOME", "Only"));
        service.delete(customerId, new AddressId(only.addressId()), 1);
        assertThat(service.list(customerId)).isEmpty();
        Document state = stateRepo.findByCustomerId(customerId.value());
        assertThat(state.getString("defaultAddressId")).isNull();
    }

    // ---------- 26/27: set-default ----------

    @Test void set_default_moves_default_to_target_and_clears_previous() {
        CustomerId customerId = uniqueCustomerId("0027");
        AddressService.AddressView first = service.create(customerId, create("HOME", "First"));
        AddressService.AddressView second = service.create(customerId, create("WORK", "Second"));
        assertThat(first.isDefault()).isTrue();
        assertThat(second.isDefault()).isFalse();

        AddressService.AddressView newDefault = service.setDefault(customerId, new AddressId(second.addressId()));
        assertThat(newDefault.isDefault()).isTrue();
        assertThat(service.get(customerId, new AddressId(first.addressId())).isDefault()).isFalse();
    }

    @Test void set_default_for_unowned_address_is_not_found() {
        CustomerId customerA = uniqueCustomerId("0028a");
        CustomerId customerB = uniqueCustomerId("0028b");
        AddressService.AddressView bAddress = service.create(customerB, create("HOME", "B"));
        assertThatThrownBy(() -> service.setDefault(customerA, new AddressId(bAddress.addressId())))
                .isInstanceOf(AddressFailure.class)
                .satisfies(e -> assertThat(((AddressFailure) e).reason()).isEqualTo(AddressFailure.Reason.NOT_FOUND));
    }

    @Test void two_concurrent_set_default_calls_yield_exactly_one_final_default() throws Exception {
        CustomerId customerId = uniqueCustomerId("0029");
        AddressService.AddressView a = service.create(customerId, create("HOME", "A"));
        AddressService.AddressView b = service.create(customerId, create("WORK", "B"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> {
                ready.countDown();
                await(go);
                service.setDefault(customerId, new AddressId(a.addressId()));
                return null;
            });
            var f2 = pool.submit(() -> {
                ready.countDown();
                await(go);
                service.setDefault(customerId, new AddressId(b.addressId()));
                return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        List<AddressService.AddressView> views = service.list(customerId);
        long defaults = views.stream().filter(AddressService.AddressView::isDefault).count();
        assertThat(defaults).as("exactly one default survives a concurrent race").isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- clock discipline ----------

    @Test void created_at_and_updated_at_come_from_the_injected_clock() {
        CustomerId customerId = uniqueCustomerId("0030");
        Instant createInstant = Instant.parse("2026-07-01T10:00:00Z");
        CLOCK_NOW.set(createInstant);
        AddressService.AddressView created = service.create(customerId, create("HOME", "Name"));
        Document doc = addressRepo.findOwnedById(customerId.value(), created.addressId());
        assertThat(doc.getDate("createdAt").toInstant()).isEqualTo(createInstant);
        assertThat(doc.getDate("updatedAt").toInstant()).isEqualTo(createInstant);

        Instant updateInstant = Instant.parse("2026-08-01T11:00:00Z");
        CLOCK_NOW.set(updateInstant);
        AddressService.PatchCommand touch = new AddressService.PatchCommand(PatchField.absent(),
                PatchField.of("Touched"), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent());
        service.patch(customerId, new AddressId(created.addressId()), 1, touch);
        Document updated = addressRepo.findOwnedById(customerId.value(), created.addressId());
        assertThat(updated.getDate("createdAt").toInstant())
                .as("createdAt never changes on update").isEqualTo(createInstant);
        assertThat(updated.getDate("updatedAt").toInstant()).isEqualTo(updateInstant);
    }

    // ---------- missing identity (stubbed real authority for a never-seeded customer) ----------

    @Test void missing_customer_identity_at_create_yields_unavailable_and_no_document() {
        CustomerId customerId = uniqueCustomerId("0031"); // never seeded into `customers`
        AddressService realService = new AddressService(addressRepo, stateRepo, limits, clock, observability,
                new FixedObjectProvider<>(realIdentityAuthority), tx);
        assertThatThrownBy(() -> realService.create(customerId, create("HOME", "Ghost")))
                .isInstanceOf(AddressFailure.class)
                .satisfies(e -> assertThat(((AddressFailure) e).reason()).isEqualTo(AddressFailure.Reason.UNAVAILABLE));
        assertThat(service.list(customerId)).isEmpty();
    }

    // ---------- Tx retry safety (mission §33/34) ----------

    @Test void transaction_retry_on_create_does_not_duplicate_the_address() {
        CustomerId customerId = uniqueCustomerId("0032");
        AtomicInteger attempts = new AtomicInteger(0);
        AddressRepository retryOnceRepo = new AddressRepository(db) {
            @Override
            public void insert(ClientSession session, Document address) {
                if (attempts.incrementAndGet() == 1) {
                    MongoException t = new MongoException("simulated transient conflict");
                    t.addLabel("TransientTransactionError");
                    throw t;
                }
                super.insert(session, address);
            }
        };
        AddressService retryingService = new AddressService(retryOnceRepo, stateRepo, limits, clock, observability,
                identityAuthorityProvider, tx);

        AddressService.AddressView created = retryingService.create(customerId, create("HOME", "Retried"));
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(service.list(customerId)).hasSize(1);
        assertThat(created.version()).isEqualTo(1);
    }

    @Test void transaction_retry_on_update_returns_the_successful_attempts_result() {
        CustomerId customerId = uniqueCustomerId("0033");
        AddressService.AddressView original = service.create(customerId, create("HOME", "Original"));
        AtomicInteger attempts = new AtomicInteger(0);
        AddressRepository retryOnceRepo = new AddressRepository(db) {
            @Override
            public Document patch(ClientSession session, String customerId, String addressId, long expectedVersion,
                                  PatchField<AddressLabel> label, PatchField<String> recipientName,
                                  PatchField<String> recipientPhone, PatchField<String> addressLine1,
                                  PatchField<String> addressLine2, PatchField<String> landmark,
                                  PatchField<String> city, PatchField<String> state, PatchField<String> postalCode,
                                  PatchField<Coordinates.Pair> coordinates, Instant now) {
                if (attempts.incrementAndGet() == 1) {
                    MongoException t = new MongoException("simulated transient conflict");
                    t.addLabel("TransientTransactionError");
                    throw t;
                }
                return super.patch(session, customerId, addressId, expectedVersion, label, recipientName,
                        recipientPhone, addressLine1, addressLine2, landmark, city, state, postalCode, coordinates,
                        now);
            }
        };
        AddressService retryingService = new AddressService(retryOnceRepo, stateRepo, limits, clock, observability,
                identityAuthorityProvider, tx);
        AddressService.PatchCommand cmd = new AddressService.PatchCommand(PatchField.absent(),
                PatchField.of("Retried Result"), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
                PatchField.absent(), PatchField.absent());

        AddressService.AddressView result = retryingService.patch(customerId, new AddressId(original.addressId()),
                1, cmd);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(result.recipientName()).isEqualTo("Retried Result");
        Document persisted = addressRepo.findOwnedById(customerId.value(), original.addressId());
        assertThat(persisted.getString("recipientName")).isEqualTo("Retried Result");
        assertThat(persisted.get("version", Number.class).longValue()).isEqualTo(result.version());
    }

    // ---------- PII-safe logging ----------

    @Test void a_repository_outage_never_logs_the_raw_exception_message() {
        CustomerId customerId = uniqueCustomerId("0034");
        String fakePii = "Sensitive Name +919999999999 CUS_leak";
        AddressRepository throwingRepo = new AddressRepository(db) {
            @Override
            public List<Document> findAllByCustomer(String customerId) {
                throw new RuntimeException(fakePii);
            }
        };
        AddressService throwingService = new AddressService(throwingRepo, stateRepo, limits, clock, observability,
                identityAuthorityProvider, tx);

        Logger logbackLogger = (Logger) LoggerFactory.getLogger(AddressService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            assertThatThrownBy(() -> throwingService.list(customerId)).isInstanceOf(AddressFailure.class);
            StringBuilder logged = new StringBuilder();
            for (ILoggingEvent event : appender.list) {
                logged.append(event.getFormattedMessage()).append('\n');
                if (event.getThrowableProxy() != null) {
                    logged.append(event.getThrowableProxy().getMessage()).append('\n');
                }
            }
            assertThat(logged.toString()).doesNotContain(fakePii).doesNotContain("Sensitive Name")
                    .doesNotContain("+919999999999").doesNotContain("CUS_leak");
        } finally {
            logbackLogger.detachAppender(appender);
        }
    }
}
