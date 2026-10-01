package com.tazzzo.membership;

import com.mongodb.MongoWriteException;
import com.mongodb.ReadPreference;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.common.money.Money;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PR-16A-1 -- the collection, its two indexes, the open-term encoding as the index sees it, and the primary pin. */
@SpringBootTest(classes = CatalogApplication.class)
class MembershipRepositoryIT extends AbstractMembershipIT {

    @Autowired MembershipRepository repository;
    @Autowired MembershipService wiredService;

    @BeforeEach
    void fresh() {
        resetFixtures();
    }

    private Map<String, Document> indexes() {
        return db.getCollection(MembershipRepository.COLLECTION).listIndexes().into(new ArrayList<>()).stream()
                .collect(Collectors.toMap(d -> d.getString("name"), d -> d));
    }

    private static Membership term(CustomerId customer, String ref) {
        return Membership.newGrant(MembershipId.generate(), customer,
                new MembershipGrantReference(GrantSource.INTERNAL_GRANT, ref), PLAN, T0);
    }

    private void insertRaw(Document d) {
        db.getCollection(MembershipRepository.COLLECTION).insertOne(d);
    }

    private static Document expiredShape(Membership m) {
        Document d = MembershipRepository.toDocument(m);
        d.put("status", "EXPIRED");
        d.remove("openTerm");
        return d;
    }

    @Test
    void bootstrap_creates_exactly_the_three_membership_indexes_and_no_ttl() {
        Map<String, Document> idx = indexes();
        assertThat(idx.keySet()).containsExactlyInAnyOrder("_id_", "membership_one_open_per_customer",
                "membership_one_per_grant_reference", "membership_active_by_customer");

        Document active = idx.get("membership_active_by_customer");
        assertThat(active.get("key", Document.class)).isEqualTo(new Document("customerId", 1).append("status", 1));
        assertThat(active.getBoolean("unique")).as("NOT a second uniqueness mechanism").isNull();
        assertThat(active.containsKey("partialFilterExpression")).isFalse();

        Document open = idx.get("membership_one_open_per_customer");
        assertThat(open.get("key", Document.class)).isEqualTo(new Document("customerId", 1));
        assertThat(open.getBoolean("unique")).isTrue();
        assertThat(open.get("partialFilterExpression", Document.class)).isEqualTo(new Document("openTerm", true));

        Document ref = idx.get("membership_one_per_grant_reference");
        assertThat(ref.get("key", Document.class)).isEqualTo(new Document("grantSource", 1).append("grantRef", 1));
        assertThat(ref.getBoolean("unique")).isTrue();
        assertThat(ref.containsKey("partialFilterExpression")).isFalse();

        assertThat(idx.values()).noneMatch(d -> d.containsKey("expireAfterSeconds"));
        assertThat(SchemaBootstrapAccess.collections()).contains("memberships");
    }

    @Test
    void bootstrap_is_idempotent() {
        schemaBootstrap.bootstrap(db);
        schemaBootstrap.bootstrap(db);
        assertThat(indexes().keySet()).containsExactlyInAnyOrder("_id_", "membership_one_open_per_customer",
                "membership_one_per_grant_reference", "membership_active_by_customer");
    }

    @Test
    void a_second_open_term_for_one_customer_is_rejected_by_the_database_itself() {
        CustomerId customer = newCustomer();
        insertRaw(MembershipRepository.toDocument(term(customer, newRef())));
        assertThatThrownBy(() -> insertRaw(MembershipRepository.toDocument(term(customer, newRef()))))
                .isInstanceOfSatisfying(MongoWriteException.class, e -> assertThat(e.getError().getCode()).isEqualTo(11000));
        // another customer is unaffected
        insertRaw(MembershipRepository.toDocument(term(newCustomer(), newRef())));
    }

    @Test
    void any_number_of_terminal_terms_may_coexist_with_one_open_term() {
        CustomerId customer = newCustomer();
        insertRaw(expiredShape(term(customer, newRef())));
        insertRaw(expiredShape(term(customer, newRef())));
        insertRaw(MembershipRepository.toDocument(term(customer, newRef())));
        assertThat(rows(customer)).hasSize(3);
    }

    @Test
    void an_openTerm_false_row_escapes_the_partial_index_which_is_why_reconstruction_rejects_it() {
        CustomerId customer = newCustomer();
        insertRaw(MembershipRepository.toDocument(term(customer, newRef())));
        Document bypass = MembershipRepository.toDocument(term(customer, newRef()));
        bypass.put("openTerm", false);
        insertRaw(bypass); // the index would NOT have stopped this
        assertThatThrownBy(() -> MembershipRepository.toMembership(bypass)).isInstanceOf(MembershipFailure.class);
        // ... and the open-term read, which includes openTerm:true, never returns it
        assertThat(repository.findOpenByCustomer(customer)).isPresent();
    }

    @Test
    void one_durable_term_per_grant_source_and_reference() {
        String ref = newRef();
        insertRaw(MembershipRepository.toDocument(term(newCustomer(), ref)));
        assertThatThrownBy(() -> insertRaw(MembershipRepository.toDocument(term(newCustomer(), ref))))
                .isInstanceOfSatisfying(MongoWriteException.class, e -> assertThat(e.getError().getCode()).isEqualTo(11000));

        // the index is composite: the SAME reference under another source namespace is a different key
        Document otherNamespace = MembershipRepository.toDocument(term(newCustomer(), ref));
        otherNamespace.put("grantSource", "SOME_FUTURE_SOURCE");
        insertRaw(otherNamespace);
        assertThatThrownBy(() -> MembershipRepository.toMembership(otherNamespace))
                .as("an unknown source fails loud on reconstruction").isInstanceOf(MembershipFailure.class);
    }

    @Test
    void the_open_term_query_is_served_by_the_partial_unique_index() {
        Document explain = db.runCommand(new Document("explain", new Document("find", MembershipRepository.COLLECTION)
                .append("filter", new Document("customerId", newCustomer().value()).append("openTerm", true)))
                .append("verbosity", "queryPlanner"));
        assertThat(explain.toJson()).contains("IXSCAN").contains("membership_one_open_per_customer")
                .doesNotContain("COLLSCAN");
    }

    @Test
    void the_entitlement_lifecycle_query_is_served_by_the_active_by_customer_index_without_a_collection_scan() {
        Document explain = db.runCommand(new Document("explain", new Document("find", MembershipRepository.COLLECTION)
                .append("filter", new Document("customerId", newCustomer().value()).append("status", "ACTIVE")))
                .append("verbosity", "queryPlanner"));
        assertThat(explain.toJson()).contains("IXSCAN").contains("membership_active_by_customer")
                .doesNotContain("COLLSCAN");
    }

    @Test
    void the_lifecycle_read_returns_a_valid_open_term_and_ignores_terminal_rows() {
        CustomerId customer = newCustomer();
        Membership open = term(customer, newRef());
        insertRaw(expiredShape(term(customer, newRef())));
        insertRaw(MembershipRepository.toDocument(open));
        assertThat(repository.findActiveByCustomer(customer)).hasValue(open);
        assertThat(repository.findActiveByCustomer(newCustomer())).isEmpty();
    }

    @Test
    void non_transactional_reads_use_a_handle_pinned_to_the_primary() {
        assertThat(repository.primaryReads().getReadPreference()).isEqualTo(ReadPreference.primary());
        assertThat(new MembershipRepository(db.withReadPreference(ReadPreference.secondaryPreferred()))
                .primaryReads().getReadPreference()).as("the pin overrides a drifted database-level preference")
                .isEqualTo(ReadPreference.primary());
    }

    @Test
    void the_real_application_context_wires_the_service_to_the_launch_plan() {
        CustomerId customer = newCustomer();
        Membership m = wiredService.grant(customer, PLAN_ID, 1, newRef());
        assertThat(m.planPrice()).isEqualTo(Money.ofInrPaise(9900));
        assertThat(m.status()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(m.billingZoneId()).isEqualTo("Asia/Kolkata");
        assertThat(repository.findOpenByCustomer(customer)).hasValue(m);
    }

    /** SchemaBootstrap.COLLECTIONS is public static; wrapped so the list check reads clearly. */
    private static final class SchemaBootstrapAccess {
        static List<String> collections() {
            return com.tazzzo.catalog.schema.SchemaBootstrap.COLLECTIONS;
        }
    }
}
