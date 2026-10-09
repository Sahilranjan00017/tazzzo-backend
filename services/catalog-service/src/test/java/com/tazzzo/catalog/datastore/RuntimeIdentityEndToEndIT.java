package com.tazzzo.catalog.datastore;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.TestActors;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tazzzo.catalog.datastore.AuthenticatedReplicaSet.DB;
import static com.tazzzo.catalog.datastore.AuthenticatedReplicaSet.MIGRATOR_USER;
import static com.tazzzo.catalog.datastore.AuthenticatedReplicaSet.RUNTIME_USER;
import static com.tazzzo.catalog.datastore.AuthenticatedReplicaSet.USER_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The WHOLE application serving real HTTP on a database it can only read and write: the runtime identity holds no schema
 * authority (see DatastorePrivilegeIT for the denials). The schema was built beforehand by the MIGRATOR identity running the
 * real migration job, and the application starts in the default {@code VERIFY} mode. If any code path on these flows issued
 * DDL, or touched the migration bookkeeping, the server would answer {@code Unauthorized} and the flow would fail.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuntimeIdentityEndToEndIT {

    static final String CMS_TOKEN = "cms-e2e-token";
    static final String BASMATI = "TZV-000001";

    static {
        AuthenticatedReplicaSet.start();
        AuthenticatedReplicaSet.resetDatabase();
        // the controlled migration job, under the migrator identity (it also applies the approved seed-schema data migration)
        try (var ignored = new SpringApplicationBuilder(CatalogApplication.class).web(WebApplicationType.NONE).run(
                "--spring.mongodb.uri=" + AuthenticatedReplicaSet.uri(MIGRATOR_USER, USER_PASSWORD, DB),
                "--spring.mongodb.database=" + DB,
                "--tazzzo.migration.mode=APPLY", "--tazzzo.migration.environment=dev", "--tazzzo.migration.exit-after-run=false",
                "--tazzzo.migration.approved-data-migrations=V0004__seed_schemas_pack_fields_not_required",
                "--tazzzo.schema.load-taxonomy-seed=false", "--tazzzo.scheduler.enabled=false",
                "--tazzzo.consumer-rate-limit.mode=DISABLED")) {
            // closed on exit
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", () -> AuthenticatedReplicaSet.uri(RUNTIME_USER, USER_PASSWORD, DB));
        r.add("spring.mongodb.database", () -> DB);
        r.add("tazzzo.migration.mode", () -> "VERIFY");
        r.add("tazzzo.migration.environment", () -> "dev");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.schema.load-taxonomy-seed", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "DISABLED");
        r.add("tazzzo.auth.cms-token", () -> CMS_TOKEN);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired MongoDatabase db;
    @Autowired TaxonomyChangeService releases;

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(CMS_TOKEN);
        return h;
    }

    private Map<String, Object> product(String id, String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("productType", "single");
        m.put("identityType", "internal");
        m.put("internalKey", key);
        m.put("brandCode", "BR-E2E");
        m.put("title", "Runtime identity " + id);
        m.put("verticalId", BASMATI);
        m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional");
        m.put("attributes", Map.of("pack_size", 5, "pack_unit", "kg"));
        m.put("evidenceRefs", List.of());
        return m;
    }

    @Test
    void the_application_serves_real_writes_and_reads_as_the_least_privilege_runtime_identity() {
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.HttpComponentsClientHttpRequestFactory());
        // a service write (catalogue_releases) and an HTTP write that mints a product in ONE transaction over several collections
        releases.recordBaseline(TestActors.TEST, "0.9.0");
        ResponseEntity<JsonNode> created = rest.exchange("http://localhost:" + port + "/api/v1/products", HttpMethod.POST,
                new HttpEntity<>(product("TZP-E2E-1", "e2e|1"), headers()), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> read = rest.exchange("http://localhost:" + port + "/api/v1/products/TZP-E2E-1", HttpMethod.GET,
                new HttpEntity<>(headers()), JsonNode.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);

        // a title patch is a compare-and-set write with its audit event, again transactional
        HttpHeaders ifMatch = headers();
        ifMatch.set("If-Match", String.valueOf(read.getBody().path("version").asInt()));
        ResponseEntity<JsonNode> patched = rest.exchange("http://localhost:" + port + "/api/v1/products/TZP-E2E-1", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("title", "Renamed by runtime"), ifMatch), JsonNode.class);
        assertThat(patched.getStatusCode().is2xxSuccessful()).as("patch status " + patched.getStatusCode()).isTrue();
        assertThat(patched.getBody().path("title").asText()).isEqualTo("Renamed by runtime");
        // a stale compare-and-set is refused (409), not applied: the version guard works under the least-privilege identity
        ResponseEntity<JsonNode> stale = rest.exchange("http://localhost:" + port + "/api/v1/products/TZP-E2E-1", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("title", "Stale write"), ifMatch), JsonNode.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // the attributed audit rows were really persisted by the runtime identity
        assertThat(db.getCollection("product_events").countDocuments(new Document("product_id", "TZP-E2E-1")
                .append("actor", new Document("$type", "object")))).isGreaterThanOrEqualTo(2);
        assertThat(db.getCollection("products").countDocuments(new Document("_id", "TZP-E2E-1"))).isEqualTo(1);
    }

    @Test
    void the_runtime_identity_serving_the_application_still_cannot_change_the_schema() {
        assertThat(db.getCollection("products").countDocuments()).isGreaterThanOrEqualTo(0);
        try {
            db.getCollection("orders").createIndex(new Document("e2e_probe", 1));
            throw new AssertionError("the runtime identity must not be able to create an index");
        } catch (com.mongodb.MongoException e) {
            assertThat(e.getCode()).isEqualTo(13);
        }
    }
}
