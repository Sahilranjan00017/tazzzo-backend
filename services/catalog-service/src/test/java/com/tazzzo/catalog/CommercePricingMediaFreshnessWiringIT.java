package com.tazzzo.catalog;

import com.tazzzo.commerce.contract.ImageRole;
import com.tazzzo.common.money.Currency;
import com.tazzzo.media.MediaAsset;
import com.tazzzo.media.MediaOwnerType;
import com.tazzzo.media.MediaService;
import com.tazzzo.media.UpsertMediaSetCommand;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-10B final review #3 — {@code commercePricingService}/{@code commerceMediaService} are
 * production Spring beans (used today only as read ports by {@code CommerceReadConfig}), so a
 * write through either one must not be able to bypass projection freshness: with
 * {@code tazzzo.freshness.enabled=true} the shared {@link com.tazzzo.catalog.repo.ProjectionRebuildQueue}
 * bean exists and both services enqueue a rebuild in the SAME transaction as the write.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommercePricingMediaFreshnessWiringIT extends AbstractConsumerIT {

    @Autowired PricingService commercePricingService;
    @Autowired MediaService commerceMediaService;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.mongodb.database", () -> "tazzzo_commerce_freshness_wiring_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.freshness.enabled", () -> "true");    // the shared queue bean exists
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "100000");
    }

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
    }

    private Document workQueueRow(String skuId) {
        return db.getCollection("work_queue")
                .find(com.mongodb.client.model.Filters.eq("_id", "card_rebuild:" + skuId)).first();
    }

    @Test void a_pricing_write_through_the_production_bean_enqueues_a_rebuild() {
        assertThat(workQueueRow("TZP-WIRE-PRICE")).as("no stale row before the write").isNull();

        commercePricingService.upsertPrice(new UpsertPriceCommand("TZP-WIRE-PRICE", 1000L, 1200L,
                Currency.INR, null, null, "test", null));

        Document row = workQueueRow("TZP-WIRE-PRICE");
        assertThat(row).as("the commerce Pricing bean must enqueue like any other production writer")
                .isNotNull();
        assertThat(row.getString("status")).isEqualTo("pending");
        assertThat(row.getString("reason")).isEqualTo("price");
    }

    @Test void a_media_write_through_the_production_bean_enqueues_a_rebuild() {
        assertThat(workQueueRow("TZP-WIRE-MEDIA")).as("no stale row before the write").isNull();

        commerceMediaService.upsertMediaSet(new UpsertMediaSetCommand(MediaOwnerType.SKU, "TZP-WIRE-MEDIA",
                List.of(new MediaAsset("a1", "wire/front.webp", ImageRole.PRIMARY, 0, "front", 800, 600,
                        "image/webp")),
                "test", null));

        Document row = workQueueRow("TZP-WIRE-MEDIA");
        assertThat(row).as("the commerce Media bean must enqueue like any other production writer")
                .isNotNull();
        assertThat(row.getString("status")).isEqualTo("pending");
        assertThat(row.getString("reason")).isEqualTo("media");
    }
}
