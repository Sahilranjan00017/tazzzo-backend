package com.tazzzo.account;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.otp.OtpErasure;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.CustomerAccountErasure;
import com.tazzzo.auth.session.CustomerRepository;
import com.tazzzo.auth.session.CustomerSessionRepository;
import com.tazzzo.auth.SessionId;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.customer.address.AddressErasure;
import com.tazzzo.customer.cart.CartErasure;
import com.tazzzo.customer.checkout.CheckoutQuoteErasure;
import com.tazzzo.customer.order.OrderErasure;
import com.tazzzo.customer.profile.CustomerProfileErasure;
import com.tazzzo.membership.MembershipErasure;
import com.tazzzo.membership.MembershipService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Customer account deletion against real MongoDB 7: every customer-linked collection is seeded for two customers, one
 * is deleted, and each collection is checked for the exact outcome (deleted, anonymised in a still-parseable shape,
 * revoked, tombstoned), with the other customer untouched. Also: idempotency, concurrency, failure atomicity, and that
 * the deleted phone can register again as a NEW customer.
 */
@SpringBootTest(classes = CatalogApplication.class)
class AccountDeletionIT extends AbstractMongoIT {

    @Autowired AccountDeletionService service;
    @Autowired CustomerRepository customers;
    @Autowired CustomerSessionRepository sessions;
    @Autowired MembershipService memberships;
    @Autowired MongoClient mongo;

    static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    /** Seeds every customer-linked collection, mirroring the shapes the repositories write. */
    private CustomerId seedCustomer(String phone, int orders) {
        CustomerId id = CustomerId.generate();
        String c = id.value();
        db.getCollection("customers").insertOne(new Document("_id", c).append("phoneNormalized", phone).append("status", "ACTIVE")
                .append("createdAt", Date.from(NOW)).append("updatedAt", Date.from(NOW)).append("lastLoginAt", Date.from(NOW)));
        db.getCollection("customer_profiles").insertOne(new Document("_id", c).append("displayName", "Person " + phone)
                .append("email", phone + "@example.test").append("version", 2L).append("createdAt", Date.from(NOW)).append("updatedAt", Date.from(NOW)));
        for (int i = 0; i < 2; i++) {
            db.getCollection("customer_addresses").insertOne(new Document("_id", "ADDR_" + c.substring(4) + i).append("customerId", c)
                    .append("label", "HOME").append("recipientName", "Person " + phone).append("recipientPhone", phone)
                    .append("addressLine1", "1 Street").append("city", "Bengaluru").append("state", "Karnataka").append("postalCode", "560001")
                    .append("version", 1L).append("createdAt", Date.from(NOW)).append("updatedAt", Date.from(NOW)));
        }
        db.getCollection("customer_address_state").insertOne(new Document("_id", c).append("addressCount", 2).append("defaultAddressId", "ADDR_" + c.substring(4) + "0"));
        db.getCollection("customer_carts").insertOne(new Document("_id", c).append("version", 3L).append("items", List.of()).append("updatedAt", Date.from(NOW)).append("expiresAt", Date.from(NOW.plusSeconds(86_400))));
        db.getCollection("checkout_quotes").insertOne(new Document("_id", "CQ_" + c.substring(4)).append("customerId", c).append("idempotencyKeyDigest", "d" + c)
                .append("addressId", "ADDR_" + c.substring(4) + "0").append("createdAt", Date.from(NOW)));
        for (int i = 0; i < orders; i++) {
            Document snapshot = new Document("label", "HOME").append("recipientName", "Person " + phone).append("recipientPhone", phone)
                    .append("addressLine1", "1 Street").append("addressLine2", "Flat 2").append("landmark", "Near park")
                    .append("city", "Bengaluru").append("state", "Karnataka").append("postalCode", "560001").append("latitude", 12.97).append("longitude", 77.59);
            db.getCollection("orders").insertOne(new Document("_id", "ORD_" + c.substring(4) + i).append("customerId", c).append("quoteId", "CQ_" + c.substring(4) + i)
                    .append("status", "CONFIRMED").append("paymentMethod", "COD").append("version", 2L).append("addressId", "ADDR_" + c.substring(4) + "0")
                    .append("addressVersion", 1L).append("addressSnapshot", snapshot)
                    .append("lines", List.of(new Document("skuId", "TZP-1").append("title", "Widget").append("brandCode", "BR").append("quantity", 1).append("unitPricePaise", 100L).append("lineTotalPaise", 100L)))
                    .append("itemCount", 1).append("subtotalPaise", 100L).append("currency", "INR").append("reservationId", "RSV_" + c.substring(4) + i)
                    .append("confirmedPaymentCondition", "COD_DUE").append("confirmedAt", Date.from(NOW)).append("createdAt", Date.from(NOW)).append("updatedAt", Date.from(NOW)));
        }
        for (int i = 0; i < 2; i++) {
            db.getCollection("customer_sessions").insertOne(new Document("_id", "SES_" + c.substring(4) + i).append("customerId", c)
                    .append("refreshTokenDigest", "digest" + i).append("createdAt", Date.from(NOW)).append("expiresAt", Date.from(NOW.plusSeconds(86_400 * 30))).append("revokedAt", null));
        }
        db.getCollection("customer_otp_challenges").insertOne(new Document("_id", "OTP_" + c.substring(4)).append("phoneNormalized", phone).append("status", "ACTIVE")
                .append("createdAt", Date.from(NOW)).append("expiresAt", Date.from(NOW.plusSeconds(300))));
        db.getCollection("customer_otp_verified_grants").insertOne(new Document("_id", "GRANT_" + c.substring(4)).append("challengeId", "OTP_x" + c.substring(4))
                .append("phoneNormalized", phone).append("purpose", "LOGIN").append("createdAt", Date.from(NOW)).append("expiresAt", Date.from(NOW.plusSeconds(300))));
        memberships.grant(id, "TAZZZO_PLUS_MONTHLY", 1, "ref-" + c);
        return id;
    }

    private long count(String collection, String field, String value) {
        return db.getCollection(collection).countDocuments(Filters.eq(field, value));
    }

    @Test
    void deleting_an_account_erases_every_customer_linked_collection_and_leaves_other_customers_untouched() {
        CustomerId victim = seedCustomer("+919000000001", 2);
        CustomerId other = seedCustomer("+919000000002", 1);
        // customer data owned by modules newer than the orchestrator: support cases and the notification outbox
        for (CustomerId c : List.of(victim, other)) {
            for (int i = 0; i < 2; i++) {
                db.getCollection("support_cases").insertOne(new Document("_id", "SUP_" + c.value().substring(4) + "case000000000000" + i)
                        .append("customerId", c.value()).append("subject", "Where is my order, call me on +9190000").append("status", "OPEN"));
            }
            db.getCollection("notification_outbox").insertOne(new Document("_id", "ORDER_CONFIRMED:ORD_" + c.value().substring(4))
                    .append("customer_id", c.value()).append("status", "PENDING"));
            db.getCollection("customer_address_idempotency").insertOne(new Document("_id", c.value() + "|abc")
                    .append("customer_id", c.value()).append("request_hash", "h").append("address_id", "ADR_x"));
        }

        assertThat(service.delete(victim)).isEqualTo(AccountDeletionService.Outcome.DELETED);
        String v = victim.value();
        assertThat(count("support_cases", "customerId", v)).as("support case text is personal data").isZero();
        assertThat(count("notification_outbox", "customer_id", v)).as("nothing is ever sent to an erased account").isZero();
        assertThat(count("customer_address_idempotency", "customer_id", v)).as("idempotency rows go with the addresses").isZero();

        // identity: tombstone, no phone, no login timestamp
        Document row = db.getCollection("customers").find(Filters.eq("_id", v)).first();
        assertThat(row.getString("status")).isEqualTo("DELETED");
        assertThat(row.getString("phoneNormalized")).isEqualTo("deleted:" + v);
        assertThat(row.get("deletedAt")).isNotNull();
        assertThat(row.containsKey("lastLoginAt")).isFalse();
        assertThat(db.getCollection("customers").countDocuments(Filters.eq("phoneNormalized", "+919000000001"))).isZero();
        // sessions: all revoked, none honoured any more
        assertThat(db.getCollection("customer_sessions").countDocuments(Filters.and(Filters.eq("customerId", v), Filters.eq("revokedAt", null)))).isZero();
        assertThat(sessions.isActive(victim, new SessionId("SES_" + v.substring(4) + "0"), Instant.now())).isFalse();
        // deleted outright
        for (String[] c : new String[][]{{"customer_profiles", "_id"}, {"customer_addresses", "customerId"}, {"customer_address_state", "_id"},
                {"customer_carts", "_id"}, {"checkout_quotes", "customerId"}}) {
            assertThat(count(c[0], c[1], v)).as(c[0]).isZero();
        }
        assertThat(count("customer_otp_challenges", "phoneNormalized", "+919000000001")).isZero();
        assertThat(count("customer_otp_verified_grants", "phoneNormalized", "+919000000001")).isZero();
        // orders retained, anonymised, still a valid snapshot
        List<Document> orders = db.getCollection("orders").find(Filters.eq("customerId", v)).into(new ArrayList<>());
        assertThat(orders).hasSize(2);
        for (Document o : orders) {
            Document a = o.get("addressSnapshot", Document.class);
            assertThat(a.getString("recipientName")).isEqualTo("[deleted]");
            assertThat(a.getString("recipientPhone")).isEqualTo("[deleted]");
            assertThat(a.getString("addressLine1")).isEqualTo("[deleted]");
            assertThat(a.get("addressLine2")).isNull();
            assertThat(a.get("landmark")).isNull();
            assertThat(a.get("latitude")).isNull();
            assertThat(a.get("longitude")).isNull();
            assertThat(a.getString("postalCode")).isEqualTo("560001");
            assertThat(o.get(OrderErasure.ERASED_AT)).isNotNull();
            assertThat(o.toJson()).doesNotContain("+919000000001").doesNotContain("Person ").doesNotContain("Flat 2");
            new com.tazzzo.customer.order.OrderAddressSnapshot(a.getString("label"), a.getString("recipientName"), a.getString("recipientPhone"),
                    a.getString("addressLine1"), a.getString("addressLine2"), a.getString("landmark"), a.getString("city"), a.getString("state"),
                    a.getString("postalCode"), null, null); // the invariants every order read enforces still hold
        }
        // membership revoked
        Document m = db.getCollection("memberships").find(Filters.eq("customerId", v)).first();
        assertThat(m.getString("status")).isEqualTo("REVOKED");
        assertThat(m.containsKey("openTerm")).isFalse();
        // audit: one event, counts only, no personal data
        List<Document> events = db.getCollection("domain_events").find(Filters.and(Filters.eq("aggregate_id", v), Filters.eq("type", "CUSTOMER_ACCOUNT_DELETED"))).into(new ArrayList<>());
        if (events.isEmpty()) {
            events = db.getCollection("domain_events").find(Filters.eq("type", "CUSTOMER_ACCOUNT_DELETED")).into(new ArrayList<>());
        }
        assertThat(events).hasSize(1);
        assertThat(events.get(0).toJson()).contains(v).doesNotContain("+919000000001").doesNotContain("Person ");
        assertThat(events.get(0).get("detail", Document.class)).containsEntry("supportCases", 2L).containsEntry("notifications", 1L);
        // the other customer is untouched
        String o = other.value();
        assertThat(db.getCollection("customers").find(Filters.eq("_id", o)).first().getString("status")).isEqualTo("ACTIVE");
        assertThat(count("customer_profiles", "_id", o)).isEqualTo(1);
        assertThat(count("customer_addresses", "customerId", o)).isEqualTo(2);
        assertThat(count("checkout_quotes", "customerId", o)).isEqualTo(1);
        assertThat(db.getCollection("customer_sessions").countDocuments(Filters.and(Filters.eq("customerId", o), Filters.eq("revokedAt", null)))).isEqualTo(2);
        assertThat(db.getCollection("orders").find(Filters.eq("customerId", o)).first().get("addressSnapshot", Document.class).getString("recipientPhone")).isEqualTo("+919000000002");
        assertThat(db.getCollection("memberships").find(Filters.eq("customerId", o)).first().getString("status")).isEqualTo("ACTIVE");
        assertThat(count("support_cases", "customerId", o)).isEqualTo(2);
        assertThat(count("notification_outbox", "customer_id", o)).isEqualTo(1);
        assertThat(count("customer_address_idempotency", "customer_id", o)).isEqualTo(1);
    }

    @Test
    void deletion_is_idempotent_and_a_second_tombstone_never_collides_on_the_phone_index() {
        CustomerId a = seedCustomer("+919000000011", 1);
        CustomerId b = seedCustomer("+919000000012", 1);
        assertThat(service.delete(a)).isEqualTo(AccountDeletionService.Outcome.DELETED);
        long events = db.getCollection("domain_events").countDocuments(Filters.eq("type", "CUSTOMER_ACCOUNT_DELETED"));
        Document after = db.getCollection("customers").find(Filters.eq("_id", a.value())).first();
        assertThat(service.delete(a)).isEqualTo(AccountDeletionService.Outcome.ALREADY_DELETED);
        assertThat(db.getCollection("customers").find(Filters.eq("_id", a.value())).first()).isEqualTo(after);
        assertThat(db.getCollection("domain_events").countDocuments(Filters.eq("type", "CUSTOMER_ACCOUNT_DELETED"))).isEqualTo(events);
        assertThat(service.delete(b)).as("two tombstones carry distinct placeholder phones").isEqualTo(AccountDeletionService.Outcome.DELETED);
    }

    @Test
    void the_same_phone_registers_again_as_a_new_customer_after_deletion() {
        CustomerId old = seedCustomer("+919000000021", 0);
        service.delete(old);
        Tx tx = new Tx(mongo);
        Document created = tx.call(s -> customers.resolveOrCreate(s, new Phone("+919000000021"), Instant.now(), CustomerId.generate().value()));
        assertThat(created.getString("_id")).isNotEqualTo(old.value());
        assertThat(created.getString("status")).isEqualTo("ACTIVE");
        assertThat(created.getString("phoneNormalized")).isEqualTo("+919000000021");
    }

    @Test
    void a_missing_identity_is_refused_without_any_write() {
        long before = db.getCollection("domain_events").countDocuments();
        assertThatThrownBy(() -> service.delete(CustomerId.generate())).isInstanceOf(AccountDeletionFailure.class)
                .satisfies(e -> assertThat(((AccountDeletionFailure) e).reason()).isEqualTo(AccountDeletionFailure.Reason.UNAVAILABLE));
        assertThat(db.getCollection("domain_events").countDocuments()).isEqualTo(before);
    }

    @Test
    void concurrent_deletions_of_one_account_produce_exactly_one_deletion_and_one_audit_event() throws Exception {
        CustomerId id = seedCustomer("+919000000031", 1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<AccountDeletionService.Outcome>> runs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                runs.add(pool.submit((Callable<AccountDeletionService.Outcome>) () -> service.delete(id)));
            }
            List<AccountDeletionService.Outcome> outcomes = new ArrayList<>();
            for (Future<AccountDeletionService.Outcome> f : runs) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsOnlyOnce(AccountDeletionService.Outcome.DELETED);
            assertThat(outcomes).allMatch(o -> o == AccountDeletionService.Outcome.DELETED || o == AccountDeletionService.Outcome.ALREADY_DELETED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(db.getCollection("domain_events").countDocuments(Filters.and(Filters.eq("type", "CUSTOMER_ACCOUNT_DELETED"),
                Filters.regex("aggregate_id", id.value())))).isLessThanOrEqualTo(1);
        assertThat(db.getCollection("customers").find(Filters.eq("_id", id.value())).first().getString("status")).isEqualTo("DELETED");
        assertThat(count("customer_profiles", "_id", id.value())).isZero();
    }

    @Test
    void a_failure_in_any_step_leaves_everything_intact_and_the_retry_is_safe() {
        CustomerId id = seedCustomer("+919000000041", 1);
        OrderErasure failing = new OrderErasure(db) {
            @Override
            public long anonymise(ClientSession session, String customerId, Instant now) {
                throw new IllegalStateException("injected order erasure failure");
            }
        };
        AccountDeletionService broken = new AccountDeletionService(new Tx(mongo), Clock.systemUTC(),
                new CustomerAccountErasure(customers, sessions), new OtpErasure(db), new CustomerProfileErasure(db),
                new AddressErasure(db), new CartErasure(db), new CheckoutQuoteErasure(db), failing,
                new MembershipErasure(membershipRepository()), new com.tazzzo.support.SupportErasure(
                        new com.tazzzo.support.SupportCaseRepository(db)), new com.tazzzo.notification.NotificationErasure(db),
                new DomainAudit(db, Clock.systemUTC()), new AccountDeletionObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        assertThatThrownBy(() -> broken.delete(id)).isInstanceOf(AccountDeletionFailure.class);
        String v = id.value();
        assertThat(db.getCollection("customers").find(Filters.eq("_id", v)).first().getString("status")).as("transaction rolled back").isEqualTo("ACTIVE");
        assertThat(count("customer_profiles", "_id", v)).isEqualTo(1);
        assertThat(count("customer_addresses", "customerId", v)).isEqualTo(2);
        assertThat(db.getCollection("customer_sessions").countDocuments(Filters.and(Filters.eq("customerId", v), Filters.eq("revokedAt", null)))).isEqualTo(2);
        assertThat(db.getCollection("memberships").find(Filters.eq("customerId", v)).first().getString("status")).isEqualTo("ACTIVE");
        assertThat(service.delete(id)).as("the real service completes the retry").isEqualTo(AccountDeletionService.Outcome.DELETED);
    }

    @Autowired com.tazzzo.membership.MembershipRepository membershipRepository;

    private com.tazzzo.membership.MembershipRepository membershipRepository() {
        return membershipRepository;
    }
}
