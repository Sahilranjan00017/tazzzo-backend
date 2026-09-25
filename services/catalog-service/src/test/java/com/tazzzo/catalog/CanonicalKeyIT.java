package com.tazzzo.catalog;

import com.tazzzo.catalog.domain.PackOf;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.schema.CanonicalKeyService;
import com.tazzzo.catalog.schema.CanonicalKey;
import com.tazzzo.catalog.schema.DiscriminatingAttributeRegistry;
import com.tazzzo.catalog.schema.Ratification;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.CanonicalKeyBackfillService;
import com.tazzzo.catalog.tx.IdentityCollisionException;
import com.tazzzo.catalog.tx.MintService;
import com.tazzzo.catalog.tx.ProductQueryService;
import com.tazzzo.catalog.tx.VariantPackService;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CAT-ID acceptance tests. The load-bearing one is p1_cross_source_convergence: it recomputes the
 * key from a SECOND source's payload alone, holding no reference to the product the first source
 * created. That recomputation IS the proof — if the test ever reuses a remembered id, it stops
 * proving anything.
 */
class CanonicalKeyIT extends AbstractMongoIT {

    @Autowired TaxonomyLoader taxonomyLoader;
    @Autowired MintService mintService;
    @Autowired VariantPackService variantPackService;
    @Autowired CanonicalKeyService canonicalKeyService;
    @Autowired DiscriminatingAttributeRegistry registry;
    @Autowired CanonicalKeyBackfillService backfillService;
    @Autowired ProductQueryService productQueryService;

    static final String V = "TZV-000001";

    @BeforeAll
    void load() {
        taxonomyLoader.load(db);
        // C-3 REVERSED: nothing is keyable until WP-0 ratifies it. This class exercises
        // derivation, so it ratifies its own vertical explicitly — standing in for a WP-0
        // ratification rather than relying on a permissive default.
        registry.ratify(new Ratification(V, "wp0-test-1.0.0", List.of("pack"), Map.of()));
    }

    @AfterAll
    void unratify() {
        // the Spring context is shared across test classes; a leaked ratification would make
        // other classes mint keys and collide.
        registry.clear();
    }

    private ProductDraft draft(String id, String key, String brand, Map<String, Object> attrs) {
        return new ProductDraft(id, "single", "internal", key, null, brand, "P " + id,
                V, "0.9.0", "provisional", attrs, List.of(), null);
    }

    /** derive() now returns the key WITH its ratification version; these tests assert the key. */
    private Optional<String> key(String vertical, String brand, Map<String, Object> attrs) {
        return canonicalKeyService.derive(vertical, brand, "single", attrs, null)
                .map(CanonicalKey::key);
    }

    private String keyOf(String id) {
        return db.getCollection("products").find(eq("_id", id)).first()
                .get("identity", Document.class).getString("canonical_key");
    }

    // ---------- determinism (U-4) ----------

    @Test
    void u4_equivalent_quantities_converge_to_one_key() {
        Optional<String> a = key(V, "BR-X", Map.of("pack_size", 5, "pack_unit", "kg"));
        Optional<String> b = key(V, "BR-X", Map.of("pack_size", 5000, "pack_unit", "g"));
        Optional<String> c = key(V, "BR-X", Map.of("pack_size", 0.5, "pack_unit", "kg"));
        assertThat(a).contains("BR-X|" + V + "|pack=5000g");
        assertThat(b).isEqualTo(a);                       // 5 kg == 5000 g, byte-identical
        assertThat(c).contains("BR-X|" + V + "|pack=500g"); // 0.5 kg == 500 g
        assertThat(key(V, "BR-X", Map.of("pack_size", 1.5, "pack_unit", "L")))
                .contains("BR-X|" + V + "|pack=1500ml");    // 1.5 L == 1500 ml
    }

    @Test
    void u4_mass_and_volume_never_interconvert_and_sizes_separate() {
        assertThat(key(V, "BR-X", Map.of("pack_size", 1000, "pack_unit", "g")))
                .isNotEqualTo(key(V, "BR-X", Map.of("pack_size", 1000, "pack_unit", "ml")));
        assertThat(key(V, "BR-X", Map.of("pack_size", 5, "pack_unit", "kg")))
                .isNotEqualTo(key(V, "BR-X", Map.of("pack_size", 10, "pack_unit", "kg")));
    }

    // ---------- Gate 2: null key, never a partial key, never a mint failure ----------

    @Test
    void gate2_unrepresentable_inputs_yield_no_key() {
        // U-4-a: dozen is NOT aliased to pieces
        assertThat(key(V, "BR-X", Map.of("pack_size", 1, "pack_unit", "dozen"))).isEmpty();
        // U-4-c: a value needing more than 3 dp is refused, not approximated
        assertThat(key(V, "BR-X", Map.of("pack_size", 0.3333333, "pack_unit", "kg"))).isEmpty();
        // fractional count is not representable
        assertThat(key(V, "BR-X", Map.of("pack_size", 2.5, "pack_unit", "pieces"))).isEmpty();
        // brand absence is an honest unknown, never a wildcard
        assertThat(key(V, "", Map.of("pack_size", 5, "pack_unit", "kg"))).isEmpty();
        // missing / non-numeric quantity
        assertThat(key(V, "BR-X", Map.of())).isEmpty();
        // unkeyable holding verticals
        assertThat(key("TZV-UNCLASSIFIED", "BR-X", Map.of("pack_size", 5, "pack_unit", "kg")))
                .isEmpty();
    }

    @Test
    void gate2_a_product_with_no_derivable_identity_STILL_MINTS() {
        mintService.mint(draft("TZP-CK-NULL", "ck|null", "BR-X",
                Map.of("pack_size", 1, "pack_unit", "dozen")));
        Document p = db.getCollection("products").find(eq("_id", "TZP-CK-NULL")).first();
        assertThat(p).as("identity absence must never block catalogue coverage").isNotNull();
        assertThat(p.get("identity", Document.class).getString("canonical_key")).isNull();
        assertThat(db.getCollection("work_queue")
                .countDocuments(eq("_id", "identity_incomplete:TZP-CK-NULL"))).isEqualTo(1);
        assertThat(db.getCollection("canonical_keys")
                .countDocuments(eq("product_id", "TZP-CK-NULL"))).isZero();
    }

    // ---------- P-1: cross-source convergence with NO crawler memory ----------

    @Test
    void p1_cross_source_convergence_without_any_caller_state() {
        // SRC-A mints. Nothing below refers to this id again.
        mintService.mint(draft("TZP-CK-SRCA", "ck|srca", "BR-INDIAGATE",
                Map.of("pack_size", 5, "pack_unit", "kg")));

        // SRC-B: key recomputed FROM ITS OWN PAYLOAD ALONE (5000 g, phrased differently).
        String recomputed = key(V, "BR-INDIAGATE", Map.of("pack_size", 5000, "pack_unit", "g"))
                .orElseThrow();
        Document found = productQueryService.findByCanonicalKey(recomputed);

        assertThat(found.getString("_id")).isEqualTo("TZP-CK-SRCA");
        assertThat(db.getCollection("canonical_keys").countDocuments(eq("_id", recomputed)))
                .as("exactly one product owns this identity").isEqualTo(1);
    }

    @Test
    void race_second_mint_on_the_same_key_collides_rather_than_duplicating() {
        mintService.mint(draft("TZP-CK-R1", "ck|r1", "BR-RACE",
                Map.of("pack_size", 2, "pack_unit", "kg")));
        assertThatThrownBy(() -> mintService.mint(draft("TZP-CK-R2", "ck|r2", "BR-RACE",
                Map.of("pack_size", 2000, "pack_unit", "g"))))
                .isInstanceOf(IdentityCollisionException.class)
                .hasMessageContaining("canonical identity");
        assertThat(db.getCollection("products").countDocuments(eq("_id", "TZP-CK-R2"))).isZero();
    }

    // ---------- U-4-h: the multipack distinction ----------

    @Test
    void u4h_variant_pack_is_distinct_from_a_single_of_the_same_total() {
        mintService.mint(draft("TZP-CK-C", "ck|c", "BR-VP",
                Map.of("pack_size", 250, "pack_unit", "ml")));
        new BundleActivator().activate(this, "TZP-CK-C");
        // a 1500ml single
        mintService.mint(draft("TZP-CK-1500", "ck|1500", "BR-VP",
                Map.of("pack_size", 1500, "pack_unit", "ml")));
        // a 6 x 250ml pack: TOTAL measure per U-4-h, so also 1500ml
        variantPackService.writeVariantPack(new ProductDraft("TZP-CK-VP", "variant_pack",
                "internal", "ck|vp", null, "BR-VP", "Pack", V, "0.9.0", "provisional",
                Map.of("pack_size", 1500, "pack_unit", "ml"), List.of(), null,
                new PackOf("TZP-CK-C", 6)));

        assertThat(keyOf("TZP-CK-1500")).isEqualTo("BR-VP|" + V + "|pack=1500ml");
        assertThat(keyOf("TZP-CK-VP")).isEqualTo("BR-VP|" + V + "|pack=1500ml|packof=6");
        assertThat(keyOf("TZP-CK-VP")).isNotEqualTo(keyOf("TZP-CK-1500"));
    }

    // ---------- WP-6 backfill ----------

    @Test
    void wp6_backfill_is_write_once_and_idempotent() {
        // a product that predates CAT-ID: strip its key, as a legacy row would be
        mintService.mint(draft("TZP-CK-BF", "ck|bf", "BR-BF",
                Map.of("pack_size", 9, "pack_unit", "kg")));
        String original = keyOf("TZP-CK-BF");
        db.getCollection("products").updateOne(eq("_id", "TZP-CK-BF"),
                new Document("$set", new Document("identity.canonical_key", null)));
        db.getCollection("canonical_keys").deleteOne(eq("_id", original));

        backfillService.seed(V);
        backfillService.runBackfillWorker(50);
        assertThat(keyOf("TZP-CK-BF")).isEqualTo(original);

        // re-running must be a no-op, not an overwrite and not an error
        backfillService.seed(V);
        backfillService.runBackfillWorker(50);
        assertThat(keyOf("TZP-CK-BF")).isEqualTo(original);
        assertThat(db.getCollection("canonical_keys").countDocuments(eq("_id", original)))
                .isEqualTo(1);
    }

    /** Small shim: lifecycle activation lives in its own service. */
    static class BundleActivator {
        void activate(CanonicalKeyIT t, String id) {
            Document p = t.db.getCollection("products").find(eq("_id", id)).first();
            t.lifecycle().activate(id, p.getInteger("version"));
        }
    }

    @Autowired com.tazzzo.catalog.tx.ProductLifecycleService lifecycleService;
    com.tazzzo.catalog.tx.ProductLifecycleService lifecycle() { return lifecycleService; }
}
