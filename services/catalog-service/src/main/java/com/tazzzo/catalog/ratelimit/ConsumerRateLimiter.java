package com.tazzzo.catalog.ratelimit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Admission control for the public consumer surface (CAT-SEC-1 Q5).
 *
 * <p><b>It knows nothing about HTTP routes.</b> The caller supplies an already-resolved identity and
 * an EXPLICIT integer cost; this class turns that into buckets and one atomic admission. Endpoint
 * vocabulary — traversal cost, {@code 1 + effective_page_size}, which path is which — belongs with
 * the endpoints, and none of them exist yet. Hard-coding future route names here would repeat the
 * mistake that made {@code /consumer/v1} obsolete before a single controller was written.
 *
 * <p>Buckets (Q5-b): the source-IP bucket is ALWAYS applied; the installation bucket is applied only
 * when a syntactically valid identifier was supplied. Every applicable bucket must pass, and the
 * debit is all-or-nothing (Q5-ATOMIC-1). The IP bucket remains the abuse backstop precisely because
 * rotating installation ids must buy nothing.
 */
public class ConsumerRateLimiter {

    static final String IP_BUCKET_PREFIX = "rl:consumer:ip:";
    static final String INSTALL_BUCKET_PREFIX = "rl:consumer:install:";

    private final RateLimitStore store;
    private final ConsumerRateLimitProperties.Bucket ipBucket;
    private final ConsumerRateLimitProperties.Bucket installationBucket;

    public ConsumerRateLimiter(RateLimitStore store,
                               ConsumerRateLimitProperties.Bucket ipBucket,
                               ConsumerRateLimitProperties.Bucket installationBucket) {
        this.store = store;
        this.ipBucket = ipBucket;
        this.installationBucket = installationBucket;
    }

    /**
     * @param clientIp resolved by {@link ClientIpResolver} — never a raw header value
     * @param installationId a validated opaque identifier, or empty
     * @param cost the explicit weight of this request
     */
    public Admission admit(String clientIp, Optional<String> installationId, int cost) {
        if (clientIp == null || clientIp.isBlank()) {
            return new Admission.Unavailable("client ip not resolved");
        }
        List<BucketSpec> buckets = new ArrayList<>(2);
        // The raw keys are built HERE and go DOWN into the store only. What comes back up is a
        // BucketObservation carrying the bounded dimension and numbers -- never the key, which
        // embeds the client IP or installation id and must never become a metric tag.
        buckets.add(new BucketSpec(BucketDimension.IP, IP_BUCKET_PREFIX + clientIp,
                ipBucket.getCapacity(), ipBucket.getRefillPerSecond()));
        installationId.ifPresent(id -> buckets.add(new BucketSpec(BucketDimension.INSTALLATION,
                INSTALL_BUCKET_PREFIX + id,
                installationBucket.getCapacity(), installationBucket.getRefillPerSecond())));
        return store.tryConsume(buckets, cost);
    }
}
