package com.tazzzo.customer.order;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.commerce.read.CatalogCardFacts;
import com.tazzzo.commerce.read.CatalogCardReadPort;
import com.tazzzo.commerce.read.CatalogCardReader;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.money.Currency;
import com.tazzzo.pricing.PriceLookup;
import com.tazzzo.pricing.PriceReadPort;
import com.tazzzo.pricing.PriceStatus;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.serviceability.ServiceabilityReadPort;
import com.tazzzo.serviceability.ServiceabilityResolution;
import com.tazzzo.serviceability.ServiceabilityService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-14B — proves the three companion transactional ports ({@code TransactionalPriceReadPort},
 * {@code TransactionalServiceabilityReadPort}, {@code TransactionalCatalogCardReadPort}):
 * (1) participate in the CALLER's own session (see a mutation before commit, correctly not-visible
 * after a rollback), (2) return results identical to the non-session read for the same data, and
 * (3) leave the existing general-purpose ports' functional-interface/lambda usability unchanged.
 */
@SpringBootTest(classes = CatalogApplication.class)
class TransactionalReadPortsIT extends AbstractMongoIT {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @Autowired MongoClient client;
    @Autowired MongoDatabase mongoDb;

    private Tx tx() {
        return new Tx(client);
    }

    // ---------- Pricing ----------

    private PricingService pricingService() {
        return new PricingService(tx(), new WritePath(db), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void seedPrice(String sku, long sellingPaise, long mrpPaise) {
        db.getCollection("price_current").insertOne(new Document("sku_id", sku)
                .append("currency", "INR").append("selling_price_paise", sellingPaise).append("mrp_paise", mrpPaise)
                .append("version", 1L).append("active", true).append("effective_from", null)
                .append("effective_to", null).append("source", "seed")
                .append("created_at", Date.from(NOW)).append("updated_at", Date.from(NOW)));
    }

    @Test void transactional_point_read_participates_in_the_callers_session() {
        db.getCollection("price_current").deleteMany(new Document());
        PricingService svc = pricingService();
        Tx tx = tx();
        PriceLookup seenInSameSession = tx.call(session -> {
            db.getCollection("price_current").insertOne(session, new Document("sku_id", "TXP-1")
                    .append("currency", "INR").append("selling_price_paise", 1000L).append("mrp_paise", 1200L)
                    .append("version", 1L).append("active", true).append("effective_from", null)
                    .append("effective_to", null).append("source", "seed")
                    .append("created_at", Date.from(NOW)).append("updated_at", Date.from(NOW)));
            // written inside THIS session, not yet committed -- the session-aware read sees it anyway.
            return svc.findCurrentPrice(session, "TXP-1");
        });
        assertThat(seenInSameSession.isUsable()).isTrue();
        assertThat(seenInSameSession.price().sellingPricePaise()).isEqualTo(1000L);
    }

    @Test void a_rolled_back_write_is_never_visible_outside_the_transaction() {
        db.getCollection("price_current").deleteMany(new Document());
        PricingService svc = pricingService();
        Tx tx = tx();
        class Abort extends RuntimeException { }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.call(session -> {
            db.getCollection("price_current").insertOne(session, new Document("sku_id", "TXP-2")
                    .append("currency", "INR").append("selling_price_paise", 500L).append("mrp_paise", 600L)
                    .append("version", 1L).append("active", true).append("effective_from", null)
                    .append("effective_to", null).append("source", "seed")
                    .append("created_at", Date.from(NOW)).append("updated_at", Date.from(NOW)));
            throw new Abort();
        })).isInstanceOf(Abort.class);
        assertThat(svc.findCurrentPrice("TXP-2").isUsable()).as("rolled back, never committed").isFalse();
    }

    @Test void transactional_point_and_batch_reads_have_exact_parity_with_the_non_session_algorithm() {
        db.getCollection("price_current").deleteMany(new Document());
        seedPrice("TXP-3", 2500, 3000);
        PricingService svc = pricingService();
        Tx tx = tx();

        PriceLookup nonSession = svc.findCurrentPrice("TXP-3");
        PriceLookup sessionAware = tx.call(session -> svc.findCurrentPrice(session, "TXP-3"));
        assertThat(sessionAware).isEqualTo(nonSession);

        Map<String, PriceLookup> nonSessionBatch = svc.findCurrentPrices(List.of("TXP-3", "TXP-MISSING"), Currency.INR);
        Map<String, PriceLookup> sessionBatch = tx.call(session ->
                svc.findCurrentPrices(session, List.of("TXP-3", "TXP-MISSING"), Currency.INR));
        assertThat(sessionBatch).isEqualTo(nonSessionBatch);
        assertThat(sessionBatch.get("TXP-3").status()).isEqualTo(PriceStatus.ACTIVE);
        assertThat(sessionBatch.get("TXP-MISSING").status()).isEqualTo(PriceStatus.MISSING);
    }

    @Test void existing_price_read_port_stub_implementing_only_the_point_read_still_compiles_and_works() {
        // PR-14B backward-compatibility proof: a caller implementing ONLY the original abstract
        // method (no session method exists on this interface) keeps working unchanged.
        PriceReadPort stub = skuId -> PriceLookup.missing();
        assertThat(stub.findCurrentPrice("ANY").status()).isEqualTo(PriceStatus.MISSING);
        assertThat(stub.findCurrentPrices(List.of("ANY"), Currency.INR).get("ANY").status())
                .isEqualTo(PriceStatus.MISSING);
    }

    // ---------- Serviceability ----------

    private ServiceabilityService serviceabilityService() {
        return new ServiceabilityService(tx(), db, new DomainAudit(db, Clock.fixed(NOW, ZoneOffset.UTC)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void seedServiceArea(String pin, String areaId, String fulfillmentLocationId) {
        db.getCollection("service_areas").insertOne(new Document("pincode", pin)
                .append("service_area_id", areaId).append("active", true)
                .append("routes", List.of(new Document("fulfillment_location_id", fulfillmentLocationId)
                        .append("priority", 1).append("active", true)))
                .append("version", 1L).append("source", "seed")
                .append("created_at", Date.from(NOW)).append("updated_at", Date.from(NOW)));
    }

    @Test void transactional_serviceability_read_participates_in_the_callers_session() {
        db.getCollection("service_areas").deleteMany(new Document());
        ServiceabilityService svc = serviceabilityService();
        Tx tx = tx();
        ServiceabilityResolution seenInSameSession = tx.call(session -> {
            db.getCollection("service_areas").insertOne(session, new Document("pincode", "560001")
                    .append("service_area_id", "SA-TX").append("active", true)
                    .append("routes", List.of(new Document("fulfillment_location_id", "FL-TX")
                            .append("priority", 1).append("active", true)))
                    .append("version", 1L).append("source", "seed")
                    .append("created_at", Date.from(NOW)).append("updated_at", Date.from(NOW)));
            return svc.resolveByPincode(session, new Pincode("560001"));
        });
        assertThat(seenInSameSession.isServiceable()).isTrue();
        assertThat(seenInSameSession.fulfillmentLocationId()).isEqualTo("FL-TX");
    }

    @Test void transactional_serviceability_read_has_exact_parity_with_the_non_session_algorithm() {
        db.getCollection("service_areas").deleteMany(new Document());
        seedServiceArea("560002", "SA-1", "FL-1");
        ServiceabilityService svc = serviceabilityService();
        Tx tx = tx();

        ServiceabilityResolution nonSession = svc.resolveByPincode(new Pincode("560002"));
        ServiceabilityResolution sessionAware = tx.call(session -> svc.resolveByPincode(session, new Pincode("560002")));
        assertThat(sessionAware).isEqualTo(nonSession);
        assertThat(sessionAware.status()).isEqualTo(ServiceabilityResolution.Status.SERVICEABLE);

        // an unserviceable PIN resolves identically both ways too.
        ServiceabilityResolution unserviceableSessionAware =
                tx.call(session -> svc.resolveByPincode(session, new Pincode("999999")));
        assertThat(unserviceableSessionAware).isEqualTo(svc.resolveByPincode(new Pincode("999999")));
    }

    @Test void existing_serviceability_read_port_consumer_still_compiles_and_works() {
        ServiceabilityReadPort stub = pin -> ServiceabilityResolution.unserviceable();
        assertThat(stub.resolveByPincode(new Pincode("560099")).isServiceable()).isFalse();
    }

    // ---------- Catalog eligibility ----------

    private CatalogCardReader catalogCardReader() {
        return new CatalogCardReader(mongoDb);
    }

    /** A validator-conformant, consumer-eligible product -- the same shape
     *  {@code AbstractConsumerIT.eligibleProduct} uses, inserted directly so eligibility is exact. */
    private void seedEligibleProduct(String skuId, String title, String brandCode) {
        Document classification = new Document("vertical_id", "V-1").append("release_id", "R1")
                .append("status", "confirmed");
        db.getCollection("products").insertOne(new Document("_id", skuId).append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", skuId))
                .append("brand_code", brandCode).append("title", title)
                .append("lifecycle", "active").append("classification", classification)
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", new Date()));
    }

    @Test void transactional_catalog_read_participates_in_the_callers_session() {
        db.getCollection("products").deleteMany(new Document());
        CatalogCardReader reader = catalogCardReader();
        Tx tx = tx();
        Document product = new Document("_id", "TZP-TX1").append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", "TZP-TX1"))
                .append("brand_code", "BR-1").append("title", "Widget").append("lifecycle", "active")
                .append("classification", new Document("vertical_id", "V-1").append("release_id", "R1")
                        .append("status", "confirmed"))
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", new Date());
        Optional<CatalogCardFacts> seenInSameSession = tx.call(session -> {
            db.getCollection("products").insertOne(session, product);
            return reader.findEligibleCard(session, "TZP-TX1");
        });
        assertThat(seenInSameSession).isPresent();
        assertThat(seenInSameSession.get().title()).isEqualTo("Widget");
    }

    @Test void transactional_catalog_read_has_exact_parity_with_the_non_session_algorithm() {
        db.getCollection("products").deleteMany(new Document());
        seedEligibleProduct("TZP-TX2", "Gadget", "BR-2");
        CatalogCardReader reader = catalogCardReader();
        Tx tx = tx();

        Optional<CatalogCardFacts> nonSession = reader.findEligibleCard("TZP-TX2");
        Optional<CatalogCardFacts> sessionAware = tx.call(session -> reader.findEligibleCard(session, "TZP-TX2"));
        assertThat(sessionAware).isEqualTo(nonSession);
        assertThat(sessionAware).isPresent();

        // an ineligible/unknown SKU resolves identically both ways too -- no second eligibility
        // algorithm.
        Optional<CatalogCardFacts> unknownSessionAware = tx.call(session -> reader.findEligibleCard(session, "NO-SUCH-SKU"));
        assertThat(unknownSessionAware).isEmpty();
        assertThat(reader.findEligibleCard("NO-SUCH-SKU")).isEmpty();
    }

    @Test void existing_catalog_card_read_port_lambda_usage_still_compiles_and_works() {
        // PR-14B backward-compatibility proof: CatalogCardReadPort remains usable as a bare lambda.
        CatalogCardReadPort lambda = skuId -> Optional.empty();
        assertThat(lambda.findEligibleCard("ANY")).isEmpty();
    }
}
