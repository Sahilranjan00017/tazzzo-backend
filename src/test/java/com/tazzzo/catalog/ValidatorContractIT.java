package com.tazzzo.catalog;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Java port of docs/contract_attack.js (executed 26/26 vs raw MongoDB 7).
 * T-RULE-1: every expected rejection asserts the failing clause token from the server's
 * errInfo — a rejection for the wrong reason is a test failure.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ValidatorContractIT extends AbstractMongoIT {

    private MongoCollection<Document> products() { return db.getCollection("products"); }

    private Document validSingle(String id) {
        return new Document("_id", id)
                .append("product_type", "single")
                .append("identity", new Document("type", "gtin"))
                .append("brand_code", "BR-NEWBRAND").append("title", "Test")
                .append("lifecycle", "draft")
                .append("classification", new Document("vertical_id", "TZV-000123")
                        .append("release_id", "1.0.0").append("status", "provisional")
                        .append("method_detail", new Document())
                        .append("evidence_refs", List.of("EV-000001")))
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "1.0.0"))
                .append("version", 1).append("created_at", new Date());
    }

    private void assertRejectedFor(String reasonToken, Runnable write) {
        assertThatThrownBy(write::run)
                .isInstanceOfSatisfying(MongoWriteException.class, e -> {
                    String details = e.getError().getDetails() == null ? e.getMessage()
                            : e.getError().getDetails().toJson() + e.getMessage();
                    assertThat(details).as("rejection must cite '%s' (T-RULE-1)", reasonToken)
                            .contains(reasonToken);
                });
    }

    @Test @Order(1)
    void at1_sku100001_with_unseen_attributes_and_two_gtins_zero_schema_drift() {
        String schemaBefore = collectionMeta();
        String indexesBefore = indexMeta();
        db.getCollection("brands").insertOne(new Document("_id", "BR-NEWBRAND").append("canonical_name", "NewBrand"));
        db.getCollection("gtin_registry").insertOne(new Document("_id", "8901111111111"));
        db.getCollection("gtin_registry").insertOne(new Document("_id", "0071111111111"));
        Document sku = validSingle("TZP-100001")
                .append("attributes", new Document("protein_g", 24).append("low_gi", true))
                .append("gtins", List.of(
                        new Document("value", "8901111111111").append("market", "IN")
                                .append("valid_from", new Date()).append("valid_to", null),
                        new Document("value", "0071111111111").append("market", "US")
                                .append("valid_from", new Date()).append("valid_to", null)));
        products().insertOne(sku);
        assertThat(collectionMeta()).isEqualTo(schemaBefore);
        assertThat(indexMeta()).isEqualTo(indexesBefore);
    }

    @Test @Order(2)
    void g6_open_enum_attribute_value_is_data_only() {
        products().insertOne(validSingle("TZP-100002")
                .append("attributes", new Document("spice_level", "extreme")));
    }

    @Test @Order(3)
    void g16_unregistered_language_rejected_registered_accepted() {
        assertRejectedFor("localized_titles", () -> products().insertOne(
                validSingle("TZP-100003").append("localized_titles", new Document("ta", "தலைப்பு"))));
        products().insertOne(validSingle("TZP-100004")
                .append("localized_titles", new Document("hi", "शीर्षक")));
    }

    @Test @Order(4)
    void i1_bundle_with_vertical_rejected() {
        Document d = validSingle("TZP-200001").append("product_type", "bundle")
                .append("bundle_contents", List.of(
                        new Document("component_product_id", "TZP-100001").append("qty", 1),
                        new Document("component_product_id", "TZP-100002").append("qty", 1)));
        assertRejectedFor("oneOf", () -> products().insertOne(d));
    }

    @Test @Order(5)
    void i2_single_with_null_vertical_rejected() {
        Document d = validSingle("TZP-200002");
        d.get("classification", Document.class).put("vertical_id", null);
        assertRejectedFor("oneOf", () -> products().insertOne(d));
    }

    @Test @Order(6)
    void i3_unknown_product_type_rejected_and_valid_bundle_accepted() {
        assertRejectedFor("product_type", () -> products().insertOne(
                validSingle("TZP-200003").append("product_type", "subscription")));
        Document bundle = validSingle("TZP-200004").append("product_type", "bundle");
        bundle.get("classification", Document.class).put("vertical_id", null);
        bundle.append("bundle_contents", List.of(
                new Document("component_product_id", "TZP-100001").append("qty", 1),
                new Document("component_product_id", "TZP-100002").append("qty", 2)));
        products().insertOne(bundle);
    }

    @Test @Order(7)
    void i5_duplicate_gtin_registry_id_rejected() {
        assertThatThrownBy(() -> db.getCollection("gtin_registry")
                .insertOne(new Document("_id", "8901111111111")))
                .isInstanceOfSatisfying(MongoWriteException.class,
                        e -> assertThat(e.getError().getCode()).isEqualTo(11000));
    }

    @Test @Order(8)
    void i6_bypass_fake_attribute_key_IS_accepted_by_mongo_proving_service_tier() {
        products().insertOne(validSingle("TZP-200005")
                .append("attributes", new Document("fake_taxonomy_category", "whatever")));
        // Accepted by design: attribute-key governance is the application invariant tier.
    }

    @Test @Order(9)
    void i7_unregistered_ext_key_rejected() {
        assertRejectedFor("ext", () -> products().insertOne(
                validSingle("TZP-200006").append("ext", new Document("rogue_plane", new Document("x", 1)))));
    }

    @Test @Order(10)
    void i9_to_i14_shape_battery() {
        Document badEv = validSingle("TZP-200007");
        badEv.get("classification", Document.class).put("evidence_refs", List.of("NOT-AN-EV"));
        assertRejectedFor("evidence_refs", () -> products().insertOne(badEv));

        Document oneComp = validSingle("TZP-200008").append("product_type", "bundle");
        oneComp.get("classification", Document.class).put("vertical_id", null);
        oneComp.append("bundle_contents", List.of(
                new Document("component_product_id", "TZP-100001").append("qty", 1)));
        assertRejectedFor("oneOf", () -> products().insertOne(oneComp));

        assertRejectedFor("additionalProperties", () -> products().insertOne(
                validSingle("TZP-200009").append("sneaky_new_column", "nope")));

        Document conf = validSingle("TZP-200010");
        conf.get("classification", Document.class).put("confidence", 1.5);
        assertRejectedFor("confidence", () -> products().insertOne(conf));

        products().insertOne(validSingle("TZP-200011").append("lifecycle", "merging")); // M3 fix real

        List<Document> gtins = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            gtins.add(new Document("value", "890" + i).append("market", "IN")
                    .append("valid_from", new Date()).append("valid_to", null));
        }
        assertRejectedFor("gtins", () -> products().insertOne(validSingle("TZP-200012").append("gtins", gtins)));
    }

    @Test @Order(11)
    void u_battery_cas_and_immutability_bypass() {
        products().updateOne(Filters.and(Filters.eq("_id", "TZP-100001"), Filters.eq("version", 1)),
                Updates.combine(Updates.set("title", "Updated"), Updates.inc("version", 1)));
        assertThat(products().find(Filters.eq("_id", "TZP-100001")).first().getString("title"))
                .isEqualTo("Updated");
        // stale CAS matches nothing
        long stale = products().updateOne(
                Filters.and(Filters.eq("_id", "TZP-100001"), Filters.eq("version", 1)),
                Updates.set("title", "Ghost")).getModifiedCount();
        assertThat(stale).isZero();
        // F-5 TIGHTENED THIS: a naked flip to variant_pack is now REJECTED, because the
        // variant_pack branch of oneOf requires pack_of. Before F-5 this update succeeded and
        // silently produced a multipack indistinguishable from a single.
        assertRejectedFor("oneOf", () -> products().updateOne(Filters.eq("_id", "TZP-100002"),
                Updates.set("product_type", "variant_pack")));
        // BYPASS (still real, now narrower): immutability is STILL unenforceable — a flip that
        // also supplies a conforming shape is accepted. The validator binds SHAPE, never
        // IMMUTABILITY; only the service tier can own that. F-5 raised the cost of the bypass,
        // it did not close it.
        long bypass = products().updateOne(Filters.eq("_id", "TZP-100002"),
                Updates.combine(Updates.set("product_type", "variant_pack"),
                        Updates.set("pack_of", new Document("component_product_id", "TZP-100001")
                                .append("qty", 6)))).getModifiedCount();
        assertThat(bypass).as("the bypass must actually modify the doc to prove the hole").isEqualTo(1);
        // and note what the validator did NOT check: the component need not exist or be active.
        // Component liveness is a service-tier invariant (VariantPackService), by design.
        assertThat(products().find(Filters.eq("_id", "TZP-100002")).first()
                .get("pack_of", Document.class).getString("component_product_id"))
                .isEqualTo("TZP-100001");
        // shape still binds on update: flipping to a contentless bundle rejected
        assertRejectedFor("oneOf", () -> products().updateOne(Filters.eq("_id", "TZP-100004"),
                Updates.set("product_type", "bundle")));
    }

    private String collectionMeta() {
        List<String> metas = new ArrayList<>();
        for (Document c : client.getDatabase("tazzzo_it").listCollections()) {
            c.remove("info"); // uuid noise
            metas.add(c.toJson());
        }
        metas.sort(String::compareTo);
        return String.join("|", metas);
    }

    private String indexMeta() {
        List<String> metas = new ArrayList<>();
        db.getCollection("products").listIndexes().forEach(i -> metas.add(i.toJson()));
        metas.sort(String::compareTo);
        return String.join("|", metas);
    }
}
