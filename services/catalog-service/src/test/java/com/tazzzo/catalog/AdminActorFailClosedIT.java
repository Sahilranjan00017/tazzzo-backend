package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.AttributeAuthoringService;
import com.tazzzo.catalog.tx.BundleService;
import com.tazzzo.catalog.tx.ClassifyService;
import com.tazzzo.catalog.tx.EvidenceService;
import com.tazzzo.catalog.tx.GtinBindService;
import com.tazzzo.catalog.tx.MergeService;
import com.tazzzo.catalog.tx.MintService;
import com.tazzzo.catalog.tx.ProductLifecycleService;
import com.tazzzo.catalog.tx.ProductUpdateService;
import com.tazzzo.catalog.tx.PublishService;
import com.tazzzo.catalog.tx.TaintService;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.catalog.tx.VariantPackService;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.ActorDocuments;
import com.tazzzo.common.audit.ActorType;
import com.tazzzo.common.audit.TestActors;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MEDIUM-1 hardening: every admin mutation service entry point FAILS CLOSED on a missing actor, before any transaction,
 * event or state write. The generic event infrastructure keeps the actor optional (legacy and non-admin events); the admin
 * service boundary owns the stronger invariant.
 */
class AdminActorFailClosedIT extends AbstractApiIT {

    /** Every service that owns an admin (INTERNAL {@code /api/**}) mutation. */
    static final List<Class<?>> ADMIN_SERVICES = List.of(MintService.class, BundleService.class, VariantPackService.class,
            ProductUpdateService.class, ClassifyService.class, PublishService.class, GtinBindService.class,
            ProductLifecycleService.class, MergeService.class, TaxonomyChangeService.class,
            AttributeAuthoringService.class, EvidenceService.class, TaintService.class);

    /** 26 controller-invoked entry points (incl. createNode) + BundleService.activate, the batch activateRelease overload, recordBaseline. */
    static final int ACTOR_TAKING_ENTRY_POINTS = 29;

    @Autowired ApplicationContext context;
    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService taxonomy;
    @Autowired ProductUpdateService updates;
    @Autowired AttributeAuthoringService attributes;
    @Autowired EvidenceService evidence;

    static final String BASMATI = "TZV-000001";

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        taxonomy.recordBaseline(TestActors.TEST, "0.9.0");
        taxonomy.openRelease(TestActors.TEST, "7.0.0", "0.9.0");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "TZP-FC-1");
        m.put("productType", "single");
        m.put("identityType", "internal");
        m.put("internalKey", "fc|1");
        m.put("brandCode", "BR-FC");
        m.put("title", "Fail Closed");
        m.put("verticalId", BASMATI);
        m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional");
        m.put("attributes", Map.of("pack_size", 5, "pack_unit", "kg"));
        m.put("evidenceRefs", List.of());
        assertThat(post("/api/v1/products", m, CMS_TOKEN, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** Document count of every collection: any write anywhere would change it. */
    private Map<String, Long> snapshot() {
        Map<String, Long> counts = new TreeMap<>();
        for (String c : db.listCollectionNames()) {
            counts.put(c, db.getCollection(c).countDocuments());
        }
        return counts;
    }

    private static Object dummy(Class<?> type) {
        if (type == int.class) return 1;
        if (type == long.class) return 1L;
        if (type == boolean.class) return false;
        if (type == double.class) return 0.5d;
        return null;
    }

    private static void assertNullActorRejected(ThrowingCall call) {
        assertThatThrownBy(call::run).isInstanceOf(NullPointerException.class).hasMessage("actor");
    }

    @FunctionalInterface
    interface ThrowingCall { void run() throws Throwable; }

    @Test
    void every_admin_service_entry_point_rejects_a_null_actor_before_any_write() {
        List<Method> entryPoints = new ArrayList<>();
        for (Class<?> service : ADMIN_SERVICES) {
            Arrays.stream(service.getDeclaredMethods())
                    .filter(m -> Modifier.isPublic(m.getModifiers()) && m.getParameterCount() > 0
                            && m.getParameterTypes()[0] == Actor.class)
                    .sorted(Comparator.comparing(Method::getName))
                    .forEach(entryPoints::add);
        }
        assertThat(entryPoints).as("every actor-taking admin entry point is covered").hasSize(ACTOR_TAKING_ENTRY_POINTS);

        Map<String, Long> before = snapshot();
        for (Method m : entryPoints) {
            Object bean = context.getBean(m.getDeclaringClass());
            Object[] args = Arrays.stream(m.getParameterTypes()).map(AdminActorFailClosedIT::dummy).toArray();
            assertNullActorRejected(() -> {
                try {
                    m.invoke(bean, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        }
        assertThat(snapshot()).as("no collection gained or lost a document: nothing was written").isEqualTo(before);
    }

    /** With VALID arguments: a null actor fails closed and writes nothing; the same call with an actor succeeds. */
    private void failsClosedThenSucceeds(ThrowingCall withNull, ThrowingCall withActor) throws Throwable {
        Map<String, Long> before = snapshot();
        assertNullActorRejected(withNull);
        assertThat(snapshot()).isEqualTo(before);
        long events = db.getCollection("product_events").countDocuments();
        withActor.run();
        assertThat(db.getCollection("product_events").countDocuments()).as("the attributed call does write").isGreaterThan(events);
    }

    private int version(String collection, String id) {
        return db.getCollection(collection).find(Filters.eq("_id", id)).first().getInteger("version");
    }

    @Test
    void product_mutation_fails_closed_on_a_null_actor() throws Throwable {
        failsClosedThenSucceeds(
                () -> updates.updateTitle(null, "TZP-FC-1", version("products", "TZP-FC-1"), "Null Actor"),
                () -> updates.updateTitle(TestActors.TEST, "TZP-FC-1", version("products", "TZP-FC-1"), "Attributed"));
        assertThat(db.getCollection("products").find(Filters.eq("_id", "TZP-FC-1")).first().getString("title"))
                .isEqualTo("Attributed");
    }

    @Test
    void taxonomy_mutation_fails_closed_on_a_null_actor() throws Throwable {
        failsClosedThenSucceeds(
                () -> taxonomy.renameNode(null, BASMATI, version("taxonomy_nodes", BASMATI), "Null Actor Rice"),
                () -> taxonomy.renameNode(TestActors.TEST, BASMATI, version("taxonomy_nodes", BASMATI), "Attributed Rice"));
    }

    @Test
    void attribute_mutation_fails_closed_on_a_null_actor() throws Throwable {
        failsClosedThenSucceeds(
                () -> attributes.createDefinition(null, "fc_moisture_pct", "number", "descriptive", null),
                () -> attributes.createDefinition(TestActors.TEST, "fc_moisture_pct", "number", "descriptive", null));
    }

    @Test
    void evidence_mutation_fails_closed_on_a_null_actor() throws Throwable {
        failsClosedThenSucceeds(
                () -> evidence.create(null, "EV-FC-1", "lab_report", "supplier", null, null, "x", null, null),
                () -> evidence.create(TestActors.TEST, "EV-FC-1", "lab_report", "supplier", null, null, "x", null, null));
    }

    @Test
    void a_product_title_patch_over_http_is_attributed_to_the_cms_service_account() {
        HttpHeaders h = headers(CMS_TOKEN);
        h.add("If-Match", String.valueOf(version("products", "TZP-FC-1")));
        ResponseEntity<JsonNode> patched = rest.exchange(url("/api/v1/products/TZP-FC-1"), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("title", "Patched Over HTTP"), h), JsonNode.class);

        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        String requestId = patched.getHeaders().getFirst("X-Request-Id");
        Document event = db.getCollection("product_events").find(Filters.and(Filters.eq("product_id", "TZP-FC-1"),
                Filters.eq("type", "PRODUCT_TITLE_UPDATED"), Filters.eq("actor.request_id", requestId))).first();
        assertThat(event).as("the PATCH's own event, matched by its request id").isNotNull();
        assertThat(ActorDocuments.fromEvent(event)).contains(new Actor(ActorType.SERVICE_ACCOUNT, "service:cms-writer",
                "shared-token:cms-writer", requestId));
    }
}
