package com.tazzzo.commerce.read;

/**
 * PR-10C — a 503 raised inside the commerce.read composition, carrying a BOUNDED, closed-vocabulary
 * {@link Category} so the commerce.api boundary can record a bounded {@code failure_class} metric
 * tag without inspecting a free-text message or importing a domain-internal exception type (which
 * ArchUnit forbids commerce.api from doing directly). The message stays internal-only, exactly like
 * {@code ConsumerFailures.Unavailable} — {@code CommerceExceptionHandler} never exposes it.
 *
 * <p>This exists ONLY to carry the classification; the actual failure translation (Pricing/
 * Inventory/Media/Serviceability domain exceptions and Mongo outages → 503) is unchanged and still
 * happens in {@link DomainReadGuard}.
 */
public final class CommerceReadUnavailableException extends RuntimeException {

    public enum Category { MONGO, PRICING, INVENTORY, MEDIA, SERVICEABILITY, FRESHNESS_NOT_READY }

    private final Category category;

    public CommerceReadUnavailableException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
