package com.tazzzo.catalog.migration;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertOneOptions;
import com.tazzzo.catalog.domain.ProductIds;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V0017: tightening the products validator's product-id patterns (pre-scan, refusal, idempotence, grammar parity). */
class ProductIdValidatorMigrationIT extends AbstractMigrationIT {

    private static final String LEGACY = "^TZP-";
    private static final String TAIL40 = "A".repeat(40);

    private static Document product(String id) {
        return new Document("_id", id).append("product_type", "single")
                .append("identity", new Document("type", "gtin"))
                .append("brand_code", "BR-TEST").append("title", "T").append("lifecycle", "draft")
                .append("classification", new Document("vertical_id", "TZV-000123")
                        .append("release_id", "1.0.0").append("status", "provisional")
                        .append("method_detail", new Document()).append("evidence_refs", List.of()))
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "1.0.0"))
                .append("version", 1).append("created_at", new Date());
    }

    private static Document bundle(String id, String c1, String c2) {
        Document d = product(id).append("product_type", "bundle")
                .append("bundle_contents", List.of(new Document("component_product_id", c1).append("qty", 1),
                        new Document("component_product_id", c2).append("qty", 1)));
        ((Document) d.get("classification")).put("vertical_id", null);
        return d;
    }

    private static Document pack(String id, String comp) {
        return product(id).append("product_type", "variant_pack")
                .append("pack_of", new Document("component_product_id", comp).append("qty", 2));
    }

    /** A database whose products validator is the PRE-V0017 one (legacy ^TZP- patterns). */
    private MongoDatabase legacyDb() {
        MongoDatabase d = scratch();
        schemaBootstrap.ensureCollections(d);
        Document schema = d.listCollections().filter(new Document("name", "products")).first()
                .get("options", Document.class).get("validator", Document.class).get("$jsonSchema", Document.class);
        Document legacy = Document.parse(schema.toJson().replace(ProductIds.MONGO_REGEX.replace("\\", "\\\\"), LEGACY));
        d.runCommand(new Document("collMod", "products").append("validator", new Document("$jsonSchema", legacy))
                .append("validationLevel", "strict").append("validationAction", "error"));
        return d;
    }

    private static void raw(MongoDatabase d, Document doc) {
        d.getCollection("products").insertOne(doc, new InsertOneOptions().bypassDocumentValidation(true));
    }

    private static Document options(MongoDatabase d) {
        return d.listCollections().filter(new Document("name", "products")).first().get("options", Document.class);
    }

    private MigrationRunner.RunReport run(MongoDatabase d) {
        return runner(d, List.of(new ProductIdValidatorMigration())).apply(target(d), MigrationRunner.Selection.all(), apply());
    }

    private static int code(Throwable t) {
        return ((MongoWriteException) t).getError().getCode();
    }

    @Test
    void conforming_data_migrates_validator_is_live_and_rerun_is_a_noop() {
        MongoDatabase d = legacyDb();
        d.getCollection("products").insertOne(product("TZP-1"));
        d.getCollection("products").insertOne(bundle("TZP-B1", "TZP-1", "TZP-" + TAIL40));
        d.getCollection("products").insertOne(pack("TZP-P1", "TZP-1"));
        d.getCollection("products").insertOne(product("TZP-M1").append("lifecycle", "merged").append("merged_into", "TZP-1"));
        Document before = options(d);

        MigrationRunner.RunReport r = run(d);

        assertThat(r.ok()).as(r.render()).isTrue();
        Document after = options(d);
        assertThat(after.toJson()).isNotEqualTo(before.toJson());
        assertThat(after.get("validator", Document.class).toJson()).contains("{1,40}").doesNotContain("\"^TZP-\"");
        assertThat(after.getString("validationLevel")).isEqualTo("strict");
        assertThat(after.getString("validationAction")).isEqualTo("error");
        assertThat(history(d, "V0017__products_validator_product_id_patterns").get("rollbackInfo", Document.class)
                .get("previous", Document.class).get("validator", Document.class).toJson()).contains("\"^TZP-\"");

        MigrationRunner.RunReport again = run(d);
        assertThat(again.ok()).isTrue();
        assertThat(options(d).toJson()).as("second run changes nothing").isEqualTo(after.toJson());
        assertThat(new ProductIdValidatorMigration().preflight(d).status()).isEqualTo(Preflight.Status.ALREADY_SATISFIED);
    }

    @Test
    void non_conforming_ids_are_refused_with_a_count_and_sample_and_nothing_changes() {
        for (String bad : new String[]{"tzp-1", "TZP-" + "A".repeat(41), "TZP-A B", "TZP-A\n", "TZP-", "TZP-A_B", "XTZP-1"}) {
            MongoDatabase d = legacyDb();
            raw(d, product("TZP-OK"));
            raw(d, product(bad));
            String legacyJson = options(d).toJson();

            MigrationRunner.RunReport r = run(d);

            assertThat(r.outcome()).as(bad).isEqualTo(MigrationRunner.Outcome.BLOCKED);
            assertThat(r.steps().get(0).blockers().get(0)).contains("1 existing products document(s)").contains("sample _id").contains("never rewritten");
            assertThat(options(d).toJson()).as("validator untouched for " + bad).isEqualTo(legacyJson);
            assertThat(d.getCollection("products").countDocuments()).isEqualTo(2);
            assertThat(d.getCollection(MigrationHistory.COLLECTION).countDocuments(new Document("_id", ProductIdValidatorMigration.ID)
                    .append("status", "APPLIED"))).isZero();
        }
    }

    @Test
    void non_conforming_nested_ids_are_refused() {
        List<Document> bads = List.of(
                bundle("TZP-B1", "TZP-1", "tzp-2"),
                bundle("TZP-B2", "TZP-1", "TZP-" + "A".repeat(41)),
                pack("TZP-P1", "TZP-A B"),
                pack("TZP-P2", "TZP-A\n"),
                product("TZP-M1").append("lifecycle", "merged").append("merged_into", "survivor-1"));
        for (Document bad : bads) {
            MongoDatabase d = legacyDb();
            raw(d, bad);
            String legacyJson = options(d).toJson();
            MigrationRunner.RunReport r = run(d);
            assertThat(r.outcome()).as(bad.getString("_id")).isEqualTo(MigrationRunner.Outcome.BLOCKED);
            assertThat(r.steps().get(0).blockers().get(0)).contains(bad.getString("_id"));
            assertThat(options(d).toJson()).isEqualTo(legacyJson);
        }
    }

    @Test
    void refusal_message_is_bounded_and_sample_is_capped() {
        MongoDatabase d = legacyDb();
        for (int i = 0; i < 12; i++) raw(d, product("bad-" + i + "x".repeat(200)));
        MigrationRunner.RunReport r = run(d);
        String blocker = r.steps().get(0).blockers().get(0);
        assertThat(blocker).contains("12 existing products document(s)");
        assertThat(blocker.length()).isLessThan(1500);
        assertThat(blocker.split("bad-").length - 1).isEqualTo(ProductIdValidatorMigration.SAMPLE_IDS);
    }

    @Test
    void after_migration_bad_ids_are_rejected_with_121_and_good_ids_pass() {
        MongoDatabase d = legacyDb();
        assertThat(run(d).ok()).isTrue();
        MongoCollection<Document> c = d.getCollection("products");
        for (String bad : new String[]{"tzp-1", "TZP-", "TZP-A\n", "TZP-" + "A".repeat(41)}) {
            assertThatThrownBy(() -> c.insertOne(product(bad))).as(bad).isInstanceOf(MongoWriteException.class)
                    .satisfies(e -> assertThat(code(e)).isEqualTo(121));
        }
        assertThatThrownBy(() -> c.insertOne(bundle("TZP-B", "TZP-1", "TZP-A\n"))).satisfies(e -> assertThat(code(e)).isEqualTo(121));
        assertThatThrownBy(() -> c.insertOne(pack("TZP-P", "TZP-" + "A".repeat(41)))).satisfies(e -> assertThat(code(e)).isEqualTo(121));
        assertThatThrownBy(() -> c.insertOne(product("TZP-M").append("merged_into", "nope"))).satisfies(e -> assertThat(code(e)).isEqualTo(121));
        c.insertOne(product("TZP-" + TAIL40));
        c.insertOne(product("TZP-a-Z-9"));
        c.insertOne(product("TZP-N").append("merged_into", null));
        c.insertOne(bundle("TZP-B", "TZP-1", "TZP-" + TAIL40));
        c.insertOne(pack("TZP-P", "TZP-" + TAIL40));
    }

    @Test
    void a_generated_validator_keys_and_level_action_are_preserved() {
        MongoDatabase d = legacyDb();
        Document schema = options(d).get("validator", Document.class).get("$jsonSchema", Document.class);
        schema.get("properties", Document.class).put("localized_titles", Document.parse(
                "{bsonType:'object', additionalProperties:false, properties:{en:{bsonType:'string'},ta:{bsonType:'string'}}}"));
        d.runCommand(new Document("collMod", "products").append("validator", new Document("$jsonSchema", schema))
                .append("validationLevel", "moderate").append("validationAction", "error"));
        assertThat(run(d).ok()).isTrue();
        Document after = options(d);
        assertThat(after.getString("validationLevel")).isEqualTo("moderate");
        assertThat(after.get("validator", Document.class).toJson()).contains("\"ta\"");
    }

    @Test
    void absent_collection_or_absent_validator_is_a_noop_not_a_failure() {
        MongoDatabase empty = scratch();
        assertThat(new ProductIdValidatorMigration().preflight(empty).status()).isEqualTo(Preflight.Status.ALREADY_SATISFIED);
        assertThat(run(empty).ok()).isTrue();
        assertThat(collectionNames(empty)).doesNotContain("products");

        MongoDatabase bare = scratch();
        bare.createCollection("products");
        assertThat(run(bare).ok()).isTrue();
        assertThat(options(bare).get("validator")).isNull();
    }

    @Test
    void fresh_database_through_the_real_registry_ends_tightened_and_registry_lists_v0017() {
        MongoDatabase d = scratch();
        MigrationRunner.RunReport r = realRunner(d).apply(target(d), MigrationRunner.Selection.all(), apply());
        assertThat(r.ok()).as(r.render()).isTrue();
        assertThat(new ProductIdValidatorMigration().validate(d)).isEmpty();
        assertThat(Migrations.defaults(schemaBootstrap, taxonomyLoader)).extracting(Migration::id).contains(ProductIdValidatorMigration.ID);
    }

    /** ~40 ids: the Java grammar and the live Mongo validator must give the same verdict for each. */
    @Test
    void java_grammar_and_mongo_validator_agree() {
        List<String> ids = List.of(
                "TZP-1", "TZP-A", "TZP-a", "TZP-0", "TZP--", "TZP-A-B", "TZP-100001", "TZP-abc-DEF-123", "TZP-" + TAIL40, "TZP-" + "-".repeat(40),
                "TZP-" + "9".repeat(40), "TZP-aZ09-",
                "TZP-", "tzp-1", "Tzp-1", "TZP1", "TZP_1", "XTZP-1", " TZP-1", "TZP-1 ", "TZP-A B", "TZP-A\n", "TZP-A\r\n", "\nTZP-A", "TZP-\nA",
                "TZP-A\t", "TZP-" + "A".repeat(41), "TZP-" + "A".repeat(100), "TZP-A_B", "TZP-A.B", "TZP-A/B", "TZP-A%20", "TZP-é", "TZP-A\u0000",
                "TZP-١", "TZP-A ", "TZP-A\n\n", "", "-", "TZP-A+B");
        assertThat(ids.size()).isGreaterThanOrEqualTo(38);
        MongoDatabase d = legacyDb();
        assertThat(run(d).ok()).isTrue();
        List<String> mismatches = new ArrayList<>();
        for (String id : ids) {
            boolean java = ProductIds.isValid(id);
            boolean mongo;
            try {
                d.getCollection("products").insertOne(product(id));
                mongo = true;
            } catch (MongoWriteException e) {
                assertThat(e.getError().getCode()).as(id).isEqualTo(121);
                mongo = false;
            }
            if (java != mongo) mismatches.add("[" + id.replace("\n", "\\n") + "] java=" + java + " mongo=" + mongo);
        }
        assertThat(mismatches).isEmpty();
        assertThat(ProductIds.isValid("TZP-A\n")).isFalse();
    }

    @Test
    void why_the_pattern_ends_in_backslash_z_a_dollar_anchor_would_accept_a_trailing_newline() {
        MongoDatabase d = scratch();
        d.createCollection("pcre");
        d.runCommand(new Document("collMod", "pcre").append("validator", new Document("$jsonSchema",
                new Document("properties", new Document("_id", new Document("bsonType", "string").append("pattern", ProductIds.REGEX))))));
        d.getCollection("pcre").insertOne(new Document("_id", "TZP-A\n")); // accepted: the '$' anchor is the trap
        assertThat(ProductIds.isValid("TZP-A\n")).isFalse();
    }
}
