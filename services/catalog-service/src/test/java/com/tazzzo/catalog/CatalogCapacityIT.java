package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.migration.IndexCatalog;
import com.tazzzo.catalog.migration.IndexSpec;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.read.CatalogCardReader;
import com.tazzzo.commerce.read.ProductCardProjectionService;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.audit.TestActors;
import com.tazzzo.common.money.Currency;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.inventory.SetInventoryCommand;
import com.tazzzo.media.MediaService;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catalogue capacity harness (completion criteria 16/17): seeds N <b>TEST DATA</b> products through the real write
 * shapes (validator-conformant product documents, the pricing and inventory services, the projection rebuild), then
 * measures the public list, product detail, search and admin list over real HTTP, the projection rebuild cost (from
 * which a full drift pass is derived), the bulk import throughput, index/collection sizes and the query plans.
 *
 * <p>Runs only when {@code TAZZZO_CAPACITY_SKUS=<N>} is set (e.g. 5000, 25000, 100000); the normal suite skips it.
 * Results are printed as a table and written to {@code target/capacity/capacity-<N>.json}. The numbers are for this
 * machine and this in-process test JVM (service and client share it): relative, not an SLA. Nothing seeded here may
 * ever be published; the database is a throwaway container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "TAZZZO_CAPACITY_SKUS", matches = "[0-9]+")
class CatalogCapacityIT extends AbstractConsumerIT {

    static final String FIXTURE_KEY_B64 = Base64.getEncoder().encodeToString(
            "capacity-cursor-fixture-key-32by".getBytes(StandardCharsets.UTF_8));
    static final String PIN = "560001";
    static final String LOC = "FL-CAP";
    static final String W = "cms-test-token";
    static final int SAMPLES = 200;
    static final int WARMUP = 20;
    static final String[] NOUNS = {"Rice", "Atta", "Dal", "Oil", "Sugar", "Salt", "Tea", "Coffee", "Biscuit", "Chips", "Juice",
            "Milk", "Paneer", "Curd", "Butter", "Ghee", "Soap", "Shampoo", "Detergent", "Toothpaste"};
    static final String[] QUALIFIERS = {"Premium", "Everyday", "Organic", "Classic", "Gold", "Fresh", "Select", "Natural"};
    static final String[] SIZES = {"500g", "1kg", "2kg", "5kg", "10kg", "200ml", "500ml", "1L"};

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.mongodb.database", () -> "tazzzo_capacity_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> W);
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> FIXTURE_KEY_B64);
        r.add("tazzzo.freshness.enabled", () -> "true");
        r.add("tazzzo.media.public-base-url", () -> "https://cdn.tazzzo.com");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "10000000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "10000000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "10000000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "10000000");
    }

    @Autowired MongoClient client;
    final Clock clock = Clock.systemUTC();
    final Random random = new Random(20261007);
    final Map<String, Object> results = new LinkedHashMap<>();
    List<String> verticals;
    List<String> skus;
    List<String> brands;

    private Tx tx() { return new Tx(client); }
    private PricingService pricing() { return new PricingService(tx(), new WritePath(db), clock); }
    private ProductCardProjectionService projector() {
        return new ProductCardProjectionService(new CatalogCardReader(db), pricing(), new MediaService(tx(), new WritePath(db), clock), db, clock);
    }

    @Test
    void measure() throws Exception {
        int n = Integer.parseInt(System.getenv("TAZZZO_CAPACITY_SKUS"));
        results.put("skus", n);
        results.put("date", Instant.now().toString());
        results.put("note", "TEST DATA; in-process test JVM; single machine; relative numbers");

        // ---------- seed ----------
        db.drop();
        schemaBootstrap.bootstrap(db);
        for (IndexSpec spec : IndexCatalog.all()) spec.create(db);
        loader.load(db);
        changes.recordBaseline(TestActors.TEST, "R1");
        verticals = db.getCollection("taxonomy_nodes").find(Filters.eq("node_type", "vertical")).projection(new Document("_id", 1))
                .map(d -> d.getString("_id")).into(new ArrayList<>());
        assertThat(verticals).isNotEmpty();
        brands = new ArrayList<>();
        for (int i = 0; i < Math.max(50, Math.min(2000, n / 20)); i++) brands.add("BR" + (1000 + i));
        new ServiceabilityService(tx(), db, new DomainAudit(db, clock), clock).upsertServiceArea(new UpsertServiceAreaCommand(
                PIN, "SA-CAP", List.of(new ServiceabilityRoute(LOC, 0, true)), "seed", null));

        long heap0 = heapUsed();
        long t0 = System.nanoTime();
        skus = new ArrayList<>(n);
        List<Document> batch = new ArrayList<>(1000);
        for (int i = 0; i < n; i++) {
            String sku = String.format("TZP-C%07d", i);
            skus.add(sku);
            batch.add(productDoc(sku, verticals.get(i % verticals.size()), brands.get(i % brands.size()), title(i)));
            if (batch.size() == 1000 || i == n - 1) {
                db.getCollection("products").insertMany(batch);
                batch.clear();
            }
        }
        long tProducts = System.nanoTime();
        PricingService pricing = pricing();
        InventoryService inventory = new InventoryService(tx(), new WritePath(db), clock);
        for (int i = 0; i < n; i++) {
            String sku = skus.get(i);
            long selling = 5_000 + random.nextInt(50_000);
            pricing.upsertPrice(new UpsertPriceCommand(sku, selling, selling + 1_000, Currency.INR, null, null, "seed", null));
            inventory.setInventory(new SetInventoryCommand(sku, LOC, 10 + random.nextInt(90), 2, 10, "seed", null));
        }
        long tPriceStock = System.nanoTime();
        ProductCardProjectionService projector = projector();
        List<Long> rebuildNs = new ArrayList<>(n);
        for (String sku : skus) {
            long s = System.nanoTime();
            projector.rebuildOne(sku);
            rebuildNs.add(System.nanoTime() - s);
        }
        long tProjection = System.nanoTime();
        results.put("seed_products_ms", ms(tProducts - t0));
        results.put("seed_price_and_stock_ms", ms(tPriceStock - tProducts));
        results.put("seed_projection_ms", ms(tProjection - tPriceStock));
        results.put("rebuild_one", stats(rebuildNs));
        double rebuildP50Ms = (double) percentile(rebuildNs, 50) / 1_000_000;
        results.put("drift_pass_estimate_minutes_sequential", Math.round(n * rebuildP50Ms / 60_000.0 * 10) / 10.0);
        results.put("heap_after_seed_mb", (heapUsed() - heap0) / (1024 * 1024));
        // a few seed verticals are outside the release's consumer-visible scope: their products have no card by design
        long cards = db.getCollection("product_card_base").countDocuments();
        List<String> eligibleVerticals = db.getCollection("product_card_base").distinct("vertical_id", String.class).into(new ArrayList<>());
        results.put("cards_projected", cards);
        results.put("verticals_seeded", verticals.size());
        results.put("verticals_consumer_visible", eligibleVerticals.size());
        assertThat(cards).isGreaterThan(n * 95L / 100);
        verticals = eligibleVerticals;

        // ---------- request timings ----------
        HttpHeaders anon = new HttpHeaders();
        anon.set("X-Tazzzo-Installation-Id", "capacity-harness-1");
        String listVertical = verticals.get(0);
        results.put("list_first_page", time("GET /v1/categories/{vertical}/products?page_size=20&pin",
                () -> get("/v1/categories/" + listVertical + "/products?page_size=20&pin=" + PIN, anon, JsonNode.class)));
        results.put("list_deep_page", time("GET list pages 1+2+3 via cursor (three sequential requests)", () -> {
            ResponseEntity<JsonNode> p1 = get("/v1/categories/" + listVertical + "/products?page_size=20&pin=" + PIN, anon, JsonNode.class);
            String c1 = p1.getBody().path("next_cursor").asText(null);
            if (c1 == null) return p1;
            ResponseEntity<JsonNode> p2 = get("/v1/categories/" + listVertical + "/products?page_size=20&pin=" + PIN + "&cursor=" + c1, anon, JsonNode.class);
            String c2 = p2.getBody().path("next_cursor").asText(null);
            return c2 == null ? p2 : get("/v1/categories/" + listVertical + "/products?page_size=20&pin=" + PIN + "&cursor=" + c2, anon, JsonNode.class);
        }));
        // only consumer-visible SKUs are sampled, so every PDP is a 200 (a SKU outside the release scope is a 404 by design)
        List<String> visible = db.getCollection("product_card_base").find().projection(new Document("sku_id", 1))
                .map(d -> d.getString("sku_id")).into(new ArrayList<>());
        results.put("pdp", time("GET /v1/products/{sku}?pin", () -> get("/v1/products/" + visible.get(random.nextInt(visible.size())) + "?pin=" + PIN, anon, JsonNode.class)));
        results.put("search_common", time("GET /v1/search?q=rice (common token)", () -> get("/v1/search?q=rice&pin=" + PIN, anon, JsonNode.class)));
        results.put("search_two_tokens", time("GET /v1/search?q=premium+rice", () -> get("/v1/search?q=premium%20rice&pin=" + PIN, anon, JsonNode.class)));
        results.put("search_brand", time("GET /v1/search?q=<brand> (rare token)", () -> get("/v1/search?q=" + brands.get(random.nextInt(brands.size())).toLowerCase() + "&pin=" + PIN, anon, JsonNode.class)));
        results.put("admin_list", time("GET /api/v1/products?verticalId&limit=50 (cms)", () -> get("/api/v1/products?verticalId=" + listVertical + "&limit=50", bearer(W), JsonNode.class)));
        results.put("admin_list_unfiltered", time("GET /api/v1/products?limit=200 (cms)", () -> get("/api/v1/products?limit=200", bearer(W), JsonNode.class)));
        results.put("admin_get", time("GET /api/v1/products/{id} (cms)", () -> get("/api/v1/products/" + skus.get(random.nextInt(n)), bearer(W), JsonNode.class)));

        // ---------- import throughput (real admin import path, new ids) ----------
        int files = Math.max(1, Math.min(4, n / 2500));
        List<Long> importNs = new ArrayList<>();
        for (int f = 0; f < files; f++) {
            List<Map<String, Object>> rows = new ArrayList<>(500);
            for (int i = 0; i < 500; i++) {
                int k = f * 500 + i;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", String.format("TZP-IMP%06d", k));
                row.put("productType", "single");
                row.put("identityType", "internal");
                row.put("internalKey", "imp|" + k);
                row.put("brandCode", brands.get(k % brands.size()));
                row.put("title", "Imported " + title(k));
                row.put("verticalId", verticals.get(k % verticals.size()));
                row.put("releaseId", "R1");
                row.put("classificationStatus", "provisional");
                row.put("attributes", Map.of());
                row.put("evidenceRefs", List.of());
                rows.add(row);
            }
            long s = System.nanoTime();
            ResponseEntity<JsonNode> r = rest.exchange(url("/api/v1/admin/imports/products"), HttpMethod.POST,
                    new HttpEntity<>(Map.of("dryRun", false, "rows", rows), json(W)), JsonNode.class);
            importNs.add(System.nanoTime() - s);
            assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
            assertThat(r.getBody().get("applied").asInt()).isEqualTo(500);
        }
        double importSeconds = importNs.stream().mapToLong(Long::longValue).sum() / 1e9;
        results.put("import_products_rows", files * 500);
        results.put("import_products_rows_per_second", Math.round(files * 500 / importSeconds));
        results.put("import_products_ms_per_500_rows", stats(importNs));

        // ---------- sizes and plans ----------
        Map<String, Object> sizes = new LinkedHashMap<>();
        for (String c : List.of("products", "product_card_base", "price_current", "price_events", "inventory", "taxonomy_nodes")) {
            Document st = db.runCommand(new Document("collStats", c));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("count", st.get("count"));
            s.put("data_mb", mb(st.get("size")));
            s.put("storage_mb", mb(st.get("storageSize")));
            s.put("index_mb", mb(st.get("totalIndexSize")));
            sizes.put(c, s);
        }
        results.put("collections", sizes);
        Map<String, Object> plans = new LinkedHashMap<>();
        plans.put("products_list_by_vertical", planSummary(db.getCollection("products")
                .find(Filters.and(Filters.eq("classification.vertical_id", listVertical), Filters.eq("lifecycle", "active"),
                        Filters.eq("classification.status", "confirmed"))).sort(new Document("_id", 1)).limit(21).explain()));
        plans.put("card_search_prefix", planSummary(db.getCollection("product_card_base")
                .find(Filters.and(Filters.regex("search_tokens", "^ric"), Filters.in("vertical_id", verticals))).limit(21).explain()));
        results.put("query_plans", plans);
        results.put("heap_end_mb", heapUsed() / (1024 * 1024));

        // ---------- report ----------
        Path out = Path.of("target", "capacity", "capacity-" + n + ".json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, new Document(results).toJson());
        StringBuilder table = new StringBuilder("\nCAPACITY RESULTS skus=" + n + "\n");
        results.forEach((k, v) -> table.append(String.format("  %-42s %s%n", k, v)));
        System.out.println(table);
        for (Object plan : plans.values()) {
            assertThat(String.valueOf(plan)).as("no collection scan on a routine query").doesNotContain("COLLSCAN");
        }
    }

    // ---------- helpers ----------

    private Document productDoc(String id, String verticalId, String brand, String title) {
        return new Document("_id", id).append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", id))
                .append("brand_code", brand).append("title", title)
                .append("lifecycle", "active")
                .append("classification", new Document("vertical_id", verticalId).append("release_id", "R1").append("status", "confirmed"))
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", new Date());
    }

    private String title(int i) {
        return QUALIFIERS[i % QUALIFIERS.length] + " " + NOUNS[(i / 7) % NOUNS.length] + " " + SIZES[(i / 3) % SIZES.length] + " " + (i % 97);
    }

    private Map<String, Object> time(String label, Supplier<ResponseEntity<JsonNode>> call) {
        for (int i = 0; i < WARMUP; i++) call.get();
        List<Long> ns = new ArrayList<>(SAMPLES);
        int non2xx = 0;
        for (int i = 0; i < SAMPLES; i++) {
            long s = System.nanoTime();
            ResponseEntity<JsonNode> r = call.get();
            ns.add(System.nanoTime() - s);
            if (!r.getStatusCode().is2xxSuccessful()) non2xx++;
        }
        Map<String, Object> m = stats(ns);
        m.put("label", label);
        m.put("non_2xx", non2xx);
        // a latency of an error body is not a latency: a misconfigured run must fail, not publish fast numbers
        assertThat(non2xx).as(label + ": every sample must be 2xx").isZero();
        return m;
    }

    private static Map<String, Object> stats(List<Long> ns) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", ns.size());
        m.put("p50_ms", percentile(ns, 50) / 1_000_000.0);
        m.put("p95_ms", percentile(ns, 95) / 1_000_000.0);
        m.put("max_ms", Collections.max(ns) / 1_000_000.0);
        return m;
    }

    private static long percentile(List<Long> ns, int p) {
        long[] a = ns.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(a);
        return a[Math.min(a.length - 1, (int) Math.ceil(p / 100.0 * a.length) - 1)];
    }

    /** The WINNING plan only (a rejected index trial must never mask a winning collection scan), with the index name. */
    private static String planSummary(Document explain) {
        Document planner = explain.get("queryPlanner", Document.class);
        Document plan = planner == null ? null : planner.get("winningPlan", Document.class);
        if (plan != null && plan.containsKey("queryPlan")) plan = plan.get("queryPlan", Document.class);   // SBE shape
        List<String> stages = new ArrayList<>();
        List<String> indexes = new ArrayList<>();
        walk(plan, stages, indexes);
        return String.join(">", stages) + (indexes.isEmpty() ? "" : " " + String.join(",", indexes));
    }

    private static void walk(Document stage, List<String> stages, List<String> indexes) {
        if (stage == null) return;
        if (stage.containsKey("stage")) stages.add(stage.getString("stage"));
        if (stage.containsKey("indexName")) indexes.add(stage.getString("indexName"));
        Object input = stage.get("inputStage");
        if (input instanceof Document d) walk(d, stages, indexes);
        Object inputs = stage.get("inputStages");
        if (inputs instanceof List<?> l) for (Object o : l) if (o instanceof Document d) walk(d, stages, indexes);
    }

    private static long heapUsed() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long ms(long ns) { return ns / 1_000_000; }

    private static double mb(Object bytes) { return bytes == null ? 0 : Math.round(((Number) bytes).doubleValue() / 1024 / 1024 * 10) / 10.0; }

    private HttpHeaders json(String token) {
        HttpHeaders h = bearer(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
