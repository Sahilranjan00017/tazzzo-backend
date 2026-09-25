package com.tazzzo.catalog;

import com.mongodb.MongoWriteException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.tazzzo.catalog.schema.ValidatorGenerator;
import org.bson.Document;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The additive-language growth path (G-4) EXECUTED end to end: an unregistered language is
 * rejected; registering it in system_config + pipeline regeneration (collMod) makes it
 * writable. Zero hand-edited validators, zero migrations, zero touched documents.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ValidatorRegenIT extends AbstractMongoIT {

    @Autowired ValidatorGenerator validatorGenerator;

    private Document tamilProduct(String id) {
        return new Document("_id", id).append("product_type", "single")
                .append("identity", new Document("type", "gtin"))
                .append("brand_code", "BR-TEST").append("title", "Regen").append("lifecycle", "draft")
                .append("localized_titles", new Document("ta", "தலைப்பு"))
                .append("classification", new Document("vertical_id", "TZV-000123")
                        .append("release_id", "1.0.0").append("status", "provisional")
                        .append("method_detail", new Document()).append("evidence_refs", List.of()))
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "1.0.0"))
                .append("version", 1).append("created_at", new Date());
    }

    @Test @Order(1)
    void unregistered_language_rejected_before_regen() {
        assertRejectedFor("localized_titles",
                () -> db.getCollection("products").insertOne(tamilProduct("TZP-REGEN-1")));
    }

    private void assertRejectedFor(String reasonToken, Runnable write) {
        assertThatThrownBy(write::run).isInstanceOfSatisfying(MongoWriteException.class, e -> {
            String details = e.getError().getDetails() == null ? e.getMessage()
                    : e.getError().getDetails().toJson() + e.getMessage();
            assertThat(details).as("T-RULE-1: rejection must cite '%s'", reasonToken).contains(reasonToken);
        });
    }

    @Test @Order(2)
    void register_language_regenerate_then_accepted() {
        db.getCollection("system_config").insertOne(new Document("config_type", "languages")
                .append("values", List.of("en", "hi", "ta")).append("version", 2));
        validatorGenerator.regenerate(db); // the T6 collMod step
        db.getCollection("products").insertOne(tamilProduct("TZP-REGEN-2")); // now writable
        assertThat(db.getCollection("products").countDocuments(new Document("_id", "TZP-REGEN-2")))
                .isEqualTo(1);
        // still closed-world: a language NOT registered stays rejected
        Document bengali = tamilProduct("TZP-REGEN-3");
        bengali.put("localized_titles", new Document("bn", "শিরোনাম"));
        assertRejectedFor("localized_titles", () -> db.getCollection("products").insertOne(bengali));
    }
}
