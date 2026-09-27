package com.tazzzo.commerce.read;

import com.tazzzo.catalog.consumer.ConsumerFailures;

import java.util.function.Supplier;

/**
 * PR-10B final review #9 — the ONE place a domain read port's typed failure (Pricing/Inventory/
 * Media/Serviceability) or a datastore outage becomes the public {@link ConsumerFailures.Unavailable}
 * (503), rather than an unmapped, scarier 500. Each domain's {@code *Exception} base is documented
 * "message is internal, never surfaced to public clients" by its own module — exactly what
 * SERVICE_UNAVAILABLE already means; this is a transient/data-quality signal, not a bug in the
 * commerce read path itself.
 *
 * <p>This lives in {@code commerce.read} (never {@code commerce.api}, which ArchUnit forbids from
 * reaching {@code pricing}/{@code inventory}/{@code media}/{@code serviceability} directly) — the
 * SAME reason {@link ProductCardRuntimeEnricher} documents that its own infrastructure exceptions
 * "propagate typed and untouched" up to "the PR-10 request layer for commerce": this class IS that
 * layer's translation point.
 *
 * <p>A genuinely unexpected programming failure (NPE, ClassCastException, etc.) is NOT one of these
 * types and is left to propagate untouched to the commerce.api catch-all 500.
 */
final class DomainReadGuard {

    private DomainReadGuard() { }

    static <T> T guard(Supplier<T> call) {
        try {
            return call.get();
        } catch (com.tazzzo.pricing.PricingException | com.tazzzo.inventory.InventoryException
                | com.tazzzo.media.MediaException | com.tazzzo.serviceability.ServiceabilityException
                | com.mongodb.MongoException e) {
            throw new ConsumerFailures.Unavailable("domain read unavailable");
        }
    }
}
