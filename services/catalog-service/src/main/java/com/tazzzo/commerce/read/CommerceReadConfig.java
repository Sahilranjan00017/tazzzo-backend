package com.tazzzo.commerce.read;

import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.consumer.ConsumerAdmissionGate;
import com.tazzzo.catalog.consumer.ConsumerCursorCodec;
import com.tazzzo.catalog.consumer.ConsumerCursorProperties;
import com.tazzzo.catalog.consumer.ConsumerProductResolver;
import com.tazzzo.catalog.consumer.ConsumerProjectionService;
import com.tazzzo.catalog.consumer.ConsumerReleaseResolver;
import com.tazzzo.catalog.consumer.ConsumerTaxonomyScopeResolver;
import com.tazzzo.catalog.consumer.ConsumerVisibilityProbe;
import com.tazzzo.catalog.repo.ProjectionRebuildQueue;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.schema.SnapshotTaxonomyReader;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.media.MediaService;
import com.tazzzo.media.MediaUrlResolver;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.serviceability.ServiceabilityService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the read-only commerce composition graph as Spring beans (PR-10B). Lives in
 * {@code commerce.read} — which MAY depend on the domain services and catalog seams — so the
 * {@code commerce.api} controller never reaches domain internals (ArchUnit). It exposes ONLY the
 * three commerce application services the controller calls; it registers NO HTTP routes and NO
 * write endpoints, and it builds the domain services solely for their READ ports.
 *
 * <p>Freshness readiness ({@code tazzzo.freshness.enabled}) is threaded into
 * {@link CommerceListService}: lists require a production-managed projection and fail closed (503)
 * otherwise. The commerce cursor codec is a private instance signed with a DISTINCT route so its
 * tokens can never be replayed on {@code /catalog/v1} — it is deliberately not a bean (that would
 * make {@code ConsumerCursorCodec} ambiguous for the consumer surface's by-type injection).
 */
@Configuration
public class CommerceReadConfig {

    private final MongoDatabase db;
    private final Tx tx;
    private final WritePath writePath;
    private final Clock clock = Clock.systemUTC();

    public CommerceReadConfig(MongoDatabase db, Tx tx, WritePath writePath) {
        this.db = db;
        this.tx = tx;
        this.writePath = writePath;
    }

    @Bean
    public MediaUrlResolver commerceMediaUrlResolver(
            @Value("${tazzzo.media.public-base-url:}") String publicBaseUrl) {
        return (publicBaseUrl == null || publicBaseUrl.isBlank())
                ? MediaUrlResolver.unconfigured() : MediaUrlResolver.of(publicBaseUrl);
    }

    /**
     * PR-10B final review #3: this is a production Spring bean exposing BOTH read and write methods
     * (used here only as a read port), so a future writer autowiring it must not be able to bypass
     * projection freshness. When {@code tazzzo.freshness.enabled=true} the queue bean exists
     * ({@link CommerceFreshnessConfig}) and is threaded through; otherwise the {@code ObjectProvider}
     * resolves to null and the pre-PR-10A no-queue constructor behavior is preserved exactly — the
     * context loads either way; only {@link CommerceListService} gates on the flag for reads.
     */
    @Bean
    public PricingService commercePricingService(ObjectProvider<ProjectionRebuildQueue> queue) {
        return new PricingService(tx, writePath, clock, queue.getIfAvailable());
    }

    @Bean
    public MediaService commerceMediaService(ObjectProvider<ProjectionRebuildQueue> queue) {
        return new MediaService(tx, writePath, clock, queue.getIfAvailable());
    }

    @Bean
    public InventoryService commerceInventoryService() {
        return new InventoryService(tx, writePath, clock);
    }

    @Bean
    public ServiceabilityService commerceServiceabilityDomainService() {
        return new ServiceabilityService(tx, db, new DomainAudit(db, clock), clock);
    }

    @Bean
    public ProductCardBaseReader commerceBaseReader() {
        return new ProductCardBaseReader(db);
    }

    @Bean
    public ProductCardRuntimeEnricher commerceEnricher(InventoryService inventory,
                                                       ServiceabilityService serviceability,
                                                       MediaUrlResolver mediaUrls) {
        return new ProductCardRuntimeEnricher(serviceability, inventory, mediaUrls);
    }

    /** PR-12C: the authoritative batch read the customer cart composes (no forked buyable logic). */
    @Bean
    public CommerceSkuBatchReader commerceSkuBatchReader(ProductCardBaseReader baseReader, PricingService pricing,
                                                         ProductCardRuntimeEnricher enricher) {
        return new CommerceSkuBatchReader(new CatalogCardReader(db), baseReader, pricing, enricher);
    }

    @Bean
    public ProductDetailRuntimeComposer commerceDetailComposer(
            ProductCardBaseReader baseReader, ProductCardRuntimeEnricher enricher,
            MediaService media, MediaUrlResolver mediaUrls, PricingService pricing,
            ConsumerProductResolver resolver) {
        CatalogProductDetailReader catalogDetail =
                new CatalogProductDetailReader(resolver, new ConsumerProjectionService(db));
        return new ProductDetailRuntimeComposer(catalogDetail, baseReader, enricher, media, mediaUrls, pricing);
    }

    @Bean
    public CommerceListService commerceListService(
            SnapshotTaxonomyReader snapshots, ConsumerTaxonomyScopeResolver scopes,
            ConsumerReleaseResolver releases, ConsumerAdmissionGate gate,
            ConsumerVisibilityProbe probe, ConsumerCursorProperties cursorProperties,
            ProductCardBaseReader baseReader, PricingService pricing,
            ProductCardRuntimeEnricher enricher,
            @Value("${tazzzo.freshness.enabled:false}") boolean freshnessReady) {
        ConsumerCursorCodec commerceCursor =
                new ConsumerCursorCodec(cursorProperties, ConsumerCursorCodec.COMMERCE_ROUTE, 1);
        return new CommerceListService(snapshots, scopes, releases, gate, probe, commerceCursor,
                baseReader, pricing, enricher, db, freshnessReady);
    }

    @Bean
    public CommercePdpService commercePdpService(ConsumerReleaseResolver releases,
                                                 ConsumerAdmissionGate gate,
                                                 ProductDetailRuntimeComposer composer,
                                                 ConsumerTaxonomyScopeResolver scopes) {
        return new CommercePdpService(releases, gate, composer, scopes);
    }

    @Bean
    public CommerceServiceabilityService commerceServiceabilityService(
            ConsumerAdmissionGate gate, ServiceabilityService serviceability) {
        return new CommerceServiceabilityService(gate, serviceability);
    }
}
