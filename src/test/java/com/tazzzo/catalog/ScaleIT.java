package com.tazzzo.catalog;

import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.WriteModel;
import org.bson.Document;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-1 (100K slice): catalogue growth is data growth. 100,000 products inserted; the
 * boring-SKU rule and index behavior verified AT scale, not asserted.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScaleIT extends AbstractMongoIT {

    private static final int N = 100_000;
    private static final String[] VERTICALS = {"TZV-000123", "TZV-000200", "TZV-000300", "TZV-000400"};

    private static String winningPlanStages(Document plan) {
        StringBuilder sb = new StringBuilder();
        while (plan != null) {
            sb.append(plan.getString("stage")).append(">");
            Document qp = plan.get("queryPlan", Document.class); // SBE wraps the plan
            if (qp != null) { plan = qp; continue; }
            plan = plan.get("inputStage", Document.class);
        }
        return sb.toString();
    }

    @Test @Order(1)
    void l1_insert_100k_products() {
        long start = System.currentTimeMillis();
        List<WriteModel<Document>> batch = new ArrayList<>(2000);
        Date now = new Date();
        for (int i = 0; i < N; i++) {
            batch.add(new InsertOneModel<>(new Document("_id", "TZP-S" + i)
                    .append("product_type", "single")
                    .append("identity", new Document("type", "internal").append("internal_key", "scale|" + i))
                    .append("brand_code", "BR-" + (i % 50)).append("title", "Scale product " + i)
                    .append("lifecycle", "active")
                    .append("classification", new Document("vertical_id", VERTICALS[i % 4])
                            .append("release_id", "1.0.0").append("status", "confirmed")
                            .append("method_detail", new Document()).append("evidence_refs", List.of()))
                    .append("attributes", new Document("pack_size", (i % 10) + 1).append("pack_unit", "kg"))
                    .append("attributes_meta", new Document("validated_release", "1.0.0"))
                    .append("version", 1).append("created_at", now)));
            if (batch.size() == 2000) {
                db.getCollection("products").bulkWrite(batch);
                batch.clear();
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        assertThat(db.getCollection("products").countDocuments()).isGreaterThanOrEqualTo(N);
        System.out.println("L-1: inserted " + N + " validated products in " + elapsed + " ms");
    }

    @Test @Order(2)
    void l1_boring_sku_rule_holds_at_scale() {
        // SKU #100001 after 100K: plain insert, zero schema/index modification
        List<String> collectionsBefore = db.listCollectionNames().into(new ArrayList<>());
        int indexesBefore = db.getCollection("products").listIndexes().into(new ArrayList<>()).size();
        db.getCollection("products").insertOne(new Document("_id", "TZP-100001-AT-SCALE")
                .append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", "scale|final"))
                .append("brand_code", "BR-NEW-AT-SCALE").append("title", "The boring SKU")
                .append("lifecycle", "draft")
                .append("classification", new Document("vertical_id", "TZV-000123")
                        .append("release_id", "1.0.0").append("status", "provisional")
                        .append("method_detail", new Document()).append("evidence_refs", List.of()))
                .append("attributes", new Document("never_seen_attribute", true))
                .append("attributes_meta", new Document("validated_release", "1.0.0"))
                .append("version", 1).append("created_at", new Date()));
        assertThat(db.listCollectionNames().into(new ArrayList<>())).isEqualTo(collectionsBefore);
        assertThat(db.getCollection("products").listIndexes().into(new ArrayList<>()))
                .hasSize(indexesBefore);
    }

    @Test @Order(3)
    void l1_category_page_query_uses_the_compound_index() {
        long start = System.currentTimeMillis();
        long count = db.getCollection("products").countDocuments(and(
                eq("classification.vertical_id", "TZV-000123"), eq("lifecycle", "active"),
                eq("classification.status", "confirmed")));
        long elapsed = System.currentTimeMillis() - start;
        assertThat(count).isGreaterThanOrEqualTo(N / 4);
        Document explain = db.runCommand(new Document("explain", new Document("find", "products")
                .append("filter", new Document("classification.vertical_id", "TZV-000123")
                        .append("lifecycle", "active").append("classification.status", "confirmed"))));
        Document winning = explain.get("queryPlanner", Document.class)
                .get("winningPlan", Document.class);
        assertThat(winningPlanStages(winning)).as("WINNING plan must be an index scan").contains("IXSCAN");
        System.out.println("L-1: category count over 100K in " + elapsed + " ms (indexed)");
        assertThat(elapsed).as("soft perf bound; generous for CI").isLessThan(15000);
    }
}
