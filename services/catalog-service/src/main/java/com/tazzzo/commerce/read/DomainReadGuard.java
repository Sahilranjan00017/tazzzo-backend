package com.tazzzo.commerce.read;

import com.tazzzo.commerce.read.CommerceReadUnavailableException.Category;

import java.util.function.Supplier;

/**
 * PR-10B final review #9 (extended PR-10C #7) — the ONE place a domain read port's typed failure
 * (Pricing/Inventory/Media/Serviceability) or a datastore outage becomes the public
 * {@link CommerceReadUnavailableException} (503), rather than an unmapped, scarier 500. Each
 * domain's {@code *Exception} base is documented "message is internal, never surfaced to public
 * clients" by its own module — exactly what SERVICE_UNAVAILABLE already means; this is a
 * transient/data-quality signal, not a bug in the commerce read path itself. PR-10C: the specific
 * {@link Category} is preserved (not collapsed to one generic message) so the commerce.api boundary
 * can record a BOUNDED {@code failure_class} metric tag.
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
        } catch (com.tazzzo.pricing.PricingException e) {
            throw new CommerceReadUnavailableException(Category.PRICING, "pricing read unavailable");
        } catch (com.tazzzo.inventory.InventoryException e) {
            throw new CommerceReadUnavailableException(Category.INVENTORY, "inventory read unavailable");
        } catch (com.tazzzo.media.MediaException e) {
            throw new CommerceReadUnavailableException(Category.MEDIA, "media read unavailable");
        } catch (com.tazzzo.serviceability.ServiceabilityException e) {
            throw new CommerceReadUnavailableException(Category.SERVICEABILITY, "serviceability read unavailable");
        } catch (com.mongodb.MongoException e) {
            throw new CommerceReadUnavailableException(Category.MONGO, "datastore read unavailable");
        }
    }
}
