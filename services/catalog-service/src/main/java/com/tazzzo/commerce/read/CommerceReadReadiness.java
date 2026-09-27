package com.tazzzo.commerce.read;

import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.consumer.ConsumerCursorCodec;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimitProperties;
import com.tazzzo.media.MediaUrlResolver;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PR-10C — a small internal readiness SEAM for the public {@code /v1} commerce read surface. NOT a
 * new public HTTP endpoint: this repository deliberately keeps {@code /actuator/**} off the wire
 * (see {@code application.yml}'s "Actuator is on the classpath for Micrometer ONLY" note and
 * {@code SurfaceClassifier}), so a component/test seam matches the established policy rather than
 * introducing a new surface.
 *
 * <p>Reports, never enforces: nothing here rejects a request — {@code CommerceListService} and
 * {@code ConsumerCursorCodec} already fail closed on their own when a dependency is actually
 * missing. This exists so an operator (or a future internal/ops-only wiring) can ask "is the
 * minimum needed to serve {@code /v1} present" without inferring it from scattered 503s.
 *
 * <p><b>Never exposes secrets or internal addresses</b> — no Mongo URI, no Redis URL, no cursor
 * key, no hostnames, no fulfillment locations. Just booleans.
 */
@Component
public class CommerceReadReadiness {

    private static final Logger log = LoggerFactory.getLogger(CommerceReadReadiness.class);

    /**
     * PR-10C final review #5 — {@code cursorSigningConfigured} is required for category-products
     * PAGINATION specifically, not for every {@code /v1} route: categories/children/PDP/
     * serviceability never touch the cursor codec and can serve without it. A single
     * {@code coreReady} that included cursor readiness would therefore be FALSE while those routes
     * are actually still serving traffic — a misleading signal. Split into two levels instead:
     *
     * @param mongoReachable a cheap {@code ping} succeeded
     * @param cursorSigningConfigured the commerce list cursor's HMAC key is configured and usable
     *        (needed by category-products pagination only)
     * @param listServingReady {@code tazzzo.freshness.enabled} — category-products specifically;
     *        categories/children/PDP/serviceability do not need this
     * @param mediaConfigured informational ONLY — imagery degrades safely (no thumbnail) when
     *        unconfigured, so this never gates {@link #baseReady()} or {@link #listReady()}
     * @param rateLimiterConfigured the consumer rate limiter is not in its fail-closed DISABLED
     *        mode (the existing ratified policy: DISABLED means the surface must not be exposed)
     */
    public record Report(boolean mongoReachable, boolean cursorSigningConfigured, boolean listServingReady,
                         boolean mediaConfigured, boolean rateLimiterConfigured) {

        /**
         * The minimum for categories/children/PDP/serviceability to serve at all — none of them
         * need cursor signing or projection freshness.
         */
        public boolean baseReady() {
            return mongoReachable && rateLimiterConfigured;
        }

        /** Category-products additionally needs cursor signing (pagination) and freshness. */
        public boolean listReady() {
            return baseReady() && cursorSigningConfigured && listServingReady;
        }
    }

    private final MongoDatabase db;
    private final ConsumerCursorCodec cursors;
    private final MediaUrlResolver mediaUrls;
    private final ConsumerRateLimitProperties.Mode rateLimitMode;
    private final boolean freshnessEnabled;

    public CommerceReadReadiness(MongoDatabase db, ConsumerCursorCodec cursors, MediaUrlResolver mediaUrls,
                                 ConsumerRateLimitProperties.Mode rateLimitMode,
                                 @Value("${tazzzo.freshness.enabled:false}") boolean freshnessEnabled) {
        this.db = db;
        this.cursors = cursors;
        this.mediaUrls = mediaUrls;
        this.rateLimitMode = rateLimitMode;
        this.freshnessEnabled = freshnessEnabled;
    }

    public Report check() {
        return new Report(pingMongo(), cursors.isReady(), freshnessEnabled, mediaUrls.isConfigured(),
                rateLimitMode != ConsumerRateLimitProperties.Mode.DISABLED);
    }

    private boolean pingMongo() {
        try {
            db.runCommand(new Document("ping", 1));
            return true;
        } catch (RuntimeException e) {
            log.warn("commerce_readiness_mongo_unreachable type={}", e.getClass().getSimpleName());
            return false;
        }
    }
}
