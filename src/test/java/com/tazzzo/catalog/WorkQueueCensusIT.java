package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.schema.DiscriminatingAttributeRegistry;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.MintService;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Work-queue composition census (read-only measurement; nothing in src/main changes).
 *
 * WHAT THIS MEASURES, AND WHAT IT DOES NOT
 * ----------------------------------------
 * Every number printed below is derived from a population this test mints itself, into a
 * throwaway Testcontainers database. They are FIXTURE figures. They describe the SHAPE the
 * write paths produce — which work items appear per product, which collapse per vertical,
 * and where confidence is and is not written. They are NOT a measurement of any real
 * corpus, and they must not be read as one: the group sizes below were chosen by the
 * author, so any ratio between them was chosen by the author too.
 *
 * The census exists because §D.4's premise — "humans only look at what the classifier is
 * unsure of" — is not currently satisfiable, and the cheapest honest way to show that is to
 * print what the write paths actually write.
 *
 * The four work_queue writers reachable from a mint/classify:
 *   identity_incomplete      MintService:89   replaceOne _id=identity_incomplete:<productId>
 *                                             -> ONE ROW PER PRODUCT
 *   validation_gap           AttributeGovernanceService:64  _id=validation_gap:<verticalId>
 *                                             -> ONE ROW PER VERTICAL, however many products
 *   attribute_incomplete     AttributeGovernanceService:73  _id=attribute_incomplete:<verticalId>
 *                                             -> ONE ROW PER VERTICAL, however many products
 *   classification_review    MintService:111 / ClassifyService:62  insertOne, NO _id
 *                                             -> ONE ROW PER CALL, no deduplication at all
 *
 * That asymmetry is the point. It is the input Q-1 needs and it is visible in the tables.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkQueueCensusIT extends AbstractApiIT {

    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;
    @Autowired DiscriminatingAttributeRegistry registry;

    /** A real, taxonomy-resident vertical (schema "rice"). */
    private static final String RICE = "TZV-000001";
    private static final String RELEASE = "0.9.0";
    private static final String ABSENT = "<ABSENT>";

    /** Fixture group sizes. Author-chosen — see the class comment. */
    private static final int A = 4;   // known vertical, pack attributes present
    private static final int B = 3;   // known vertical, pack attributes MISSING
    private static final int C = 2;   // TZV-UNCLASSIFIED
    private static final int D = 1;   // TZV-SCOPE-BLOCKED

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        releases.recordBaseline(RELEASE);
    }

    // ------------------------------------------------------------------ minting

    private Map<String, Object> draft(String id, String key, String verticalId,
                                      String classificationStatus, Map<String, Object> attributes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("productType", "single");
        m.put("identityType", "internal");
        m.put("internalKey", key);
        m.put("brandCode", "BR-CENSUS");
        m.put("title", "Census " + id);
        m.put("verticalId", verticalId);
        m.put("releaseId", RELEASE);
        m.put("classificationStatus", classificationStatus);
        m.put("attributes", attributes);
        m.put("evidenceRefs", List.of());
        return m;
    }

    private void mint(String id, String verticalId, String status, Map<String, Object> attrs) {
        ResponseEntity<JsonNode> res = post("/api/v1/products",
                draft(id, "census|" + id, verticalId, status, attrs), CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode()).as("mint %s must succeed; body=%s", id, res.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test @Order(1)
    void mint_the_fixture_population() {
        // Guard on the segment's precondition: zero verticals ratified. If this ever fails,
        // the whole census is measuring a different system and the numbers below are void.
        assertThat(registry.forVertical(RICE)).as("no vertical may be ratified (WP-0)").isEmpty();
        assertThat(db.getCollection("discriminating_attributes").countDocuments())
                .as("registry source collection must be empty").isZero();

        Map<String, Object> withPack = Map.of("pack_size", 5, "pack_unit", "kg");

        for (int i = 0; i < A; i++) mint("TZP-CEN-A" + i, RICE, "provisional", withPack);
        // Group B relies on TaxonomyLoader:75-80 (U-4-f) having flipped pack_size/pack_unit to
        // required:false. If that regressed, these mints would 422 and the assertion in mint()
        // would say so rather than the census quietly losing a group.
        for (int i = 0; i < B; i++) mint("TZP-CEN-B" + i, RICE, "provisional", Map.of());
        for (int i = 0; i < C; i++) mint("TZP-CEN-C" + i, MintService.UNCLASSIFIED, "review", Map.of());
        for (int i = 0; i < D; i++) mint("TZP-CEN-D" + i, MintService.SCOPE_BLOCKED, "scope_blocked", Map.of());

        assertThat(db.getCollection("products").countDocuments()).isEqualTo(A + B + C + D);
    }

    // ------------------------------------------------------------------ the census

    @Test @Order(2)
    void census_as_minted() {
        banner("CENSUS 1 — AFTER MINT ONLY (fixture population, no classify call yet)");
        printPopulation();
        printWorkQueueByTypeAndStatus();
        printConfidenceDistribution();
        printClassificationReviewReachability();

        // The finding the segment exists to establish, asserted rather than narrated.
        assertThat(confidenceDistribution().keySet())
                .as("no minted product carries a confidence — ProductDocuments.fromDraft:20-25 "
                        + "builds `classification` with no confidence key at all")
                .containsExactly(ABSENT);
    }

    @Test @Order(3)
    void census_after_a_classify_that_omits_confidence() {
        // ProductController:98 — `body.confidence() == null ? 1.0 : body.confidence()`.
        // The request below omits the field entirely; the DTO Double arrives null.
        Map<String, Object> noConfidence = new LinkedHashMap<>();
        noConfidence.put("verticalId", RICE);
        noConfidence.put("releaseId", RELEASE);
        noConfidence.put("status", "confirmed");
        noConfidence.put("evidenceRefs", List.of());
        assertThat(post("/api/v1/products/TZP-CEN-A0/classify", noConfidence, CMS_TOKEN, JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second call: a product that is ALREADY in classification_review, re-classified to the
        // same unclassifiable vertical. ClassifyService:62 inserts with no _id, so this appends a
        // SECOND review row for one product rather than updating the first.
        Map<String, Object> stillUnclassified = new LinkedHashMap<>();
        stillUnclassified.put("verticalId", MintService.UNCLASSIFIED);
        stillUnclassified.put("releaseId", RELEASE);
        stillUnclassified.put("status", "review");
        stillUnclassified.put("evidenceRefs", List.of());
        assertThat(post("/api/v1/products/TZP-CEN-C0/classify", stillUnclassified, CMS_TOKEN, JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        banner("CENSUS 2 — AFTER TWO CLASSIFY CALLS, BOTH OMITTING `confidence`");
        printPopulation();
        printWorkQueueByTypeAndStatus();
        printConfidenceDistribution();
        printClassificationReviewReachability();

        Document a0 = product("TZP-CEN-A0");
        assertThat(a0.get("classification", Document.class).get("confidence"))
                .as("omitted confidence is substituted with 1.0 at ProductController:98 — the "
                        + "stored value records the controller default, not any classifier")
                .isEqualTo(1.0d);

        assertThat(confidenceDistribution().keySet())
                .as("the whole population is now exactly {absent, 1.0} — there is no signal to sort on")
                .containsExactlyInAnyOrder(ABSENT, "1.0");

        long reviewsForC0 = db.getCollection("work_queue").countDocuments(
                new Document("type", "classification_review").append("product_id", "TZP-CEN-C0"));
        assertThat(reviewsForC0)
                .as("classification_review has no dedup key (ClassifyService:62 / MintService:111), "
                        + "so one product accumulates one row per call")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ printers

    private Document product(String id) {
        return db.getCollection("products").find(new Document("_id", id)).first();
    }

    private void banner(String title) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println(title);
        System.out.println("FIXTURE DATA — minted by this test. Not a real corpus.");
        System.out.println("================================================================");
    }

    private void printPopulation() {
        System.out.println();
        System.out.println("-- fixture groups (author-chosen sizes) --");
        System.out.printf("  A  known vertical %s, pack attrs present : %d%n", RICE, A);
        System.out.printf("  B  known vertical %s, pack attrs MISSING : %d%n", RICE, B);
        System.out.printf("  C  %-28s          : %d%n", MintService.UNCLASSIFIED, C);
        System.out.printf("  D  %-28s          : %d%n", MintService.SCOPE_BLOCKED, D);
        System.out.printf("  products total                            : %d%n",
                db.getCollection("products").countDocuments());
    }

    /** DONE WHEN 1 — a count for every (type, status) pair present. */
    private void printWorkQueueByTypeAndStatus() {
        Map<String, Integer> counts = new TreeMap<>();
        db.getCollection("work_queue").find().forEach(d ->
                counts.merge(String.valueOf(d.get("type")) + " | " + String.valueOf(d.get("status")),
                        1, Integer::sum));
        System.out.println();
        System.out.println("-- work_queue by (type, status) --");
        System.out.printf("  %-46s %6s%n", "TYPE | STATUS", "COUNT");
        int total = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            System.out.printf("  %-46s %6d%n", e.getKey(), e.getValue());
            total += e.getValue();
        }
        if (counts.isEmpty()) System.out.println("  (empty)");
        System.out.printf("  %-46s %6d%n", "TOTAL", total);
    }

    /** DONE WHEN 2 — absent count plus a count at each distinct present value. */
    private Map<String, Integer> confidenceDistribution() {
        Map<String, Integer> counts = new TreeMap<>();
        db.getCollection("products").find().forEach(d -> {
            Document c = d.get("classification", Document.class);
            Object v = c == null ? null : (c.containsKey("confidence") ? c.get("confidence") : null);
            boolean present = c != null && c.containsKey("confidence");
            counts.merge(present ? String.valueOf(v) : ABSENT, 1, Integer::sum);
        });
        return counts;
    }

    private void printConfidenceDistribution() {
        Map<String, Integer> counts = confidenceDistribution();
        System.out.println();
        System.out.println("-- products.classification.confidence distribution --");
        System.out.printf("  %-46s %6s%n", "VALUE", "COUNT");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            System.out.printf("  %-46s %6d%n", e.getKey(), e.getValue());
        }
        System.out.printf("  %-46s %6d%n", "TOTAL", db.getCollection("products").countDocuments());
        System.out.println("  (<ABSENT> = the `confidence` key is not present on the document at all,");
        System.out.println("   which is distinct from present-and-null. ProductDocuments.fromDraft");
        System.out.println("   never writes the key; the validator permits it but does not require it.)");
    }

    /**
     * DONE WHEN 3 — how many classification_review items exist, and whether a confidence
     * value is reachable FROM THE ITEM ITSELF (i.e. without joining to another collection).
     */
    private void printClassificationReviewReachability() {
        List<Document> items = db.getCollection("work_queue")
                .find(new Document("type", "classification_review")).into(new ArrayList<>());
        TreeSet<String> fields = new TreeSet<>();
        for (Document d : items) fields.addAll(d.keySet());

        System.out.println();
        System.out.println("-- classification_review items: is confidence reachable from the item? --");
        System.out.printf("  items                        : %d%n", items.size());
        System.out.printf("  distinct product_ids         : %d%n",
                items.stream().map(d -> d.getString("product_id")).distinct().count());
        System.out.printf("  union of fields on the items : %s%n", fields);
        System.out.printf("  item carries `confidence`?   : %s%n", fields.contains("confidence"));
        System.out.println("  per item — product_id, then the confidence on the JOINED product:");
        for (Document d : items) {
            String pid = d.getString("product_id");
            Document p = product(pid);
            Document c = p == null ? null : p.get("classification", Document.class);
            String conf = (c != null && c.containsKey("confidence"))
                    ? String.valueOf(c.get("confidence")) : ABSENT;
            System.out.printf("    _id=%-40s product=%-14s products.classification.confidence=%s%n",
                    String.valueOf(d.get("_id")), pid, conf);
        }
        System.out.println("  NOTE: reaching a confidence at all requires a join to `products`.");
        System.out.println("  The work item holds no confidence, so a queue sorted confidence-ascending");
        System.out.println("  cannot be served from work_queue alone.");
    }
}
