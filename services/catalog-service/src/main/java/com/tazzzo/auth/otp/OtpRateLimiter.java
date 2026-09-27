package com.tazzzo.auth.otp;

import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.ratelimit.BucketDimension;
import com.tazzzo.catalog.ratelimit.BucketSpec;
import com.tazzzo.catalog.ratelimit.RateLimitStore;

import java.util.List;

/**
 * PR-11B — admission control for the OTP request/verify endpoints, mirroring
 * {@code ConsumerRateLimiter}'s shape exactly: it knows nothing about HTTP, takes an
 * already-resolved identity plus an explicit cost, and turns that into an atomic all-or-nothing
 * bucket charge on the SAME shared {@link RateLimitStore} the consumer surface uses (no separate
 * Redis wiring — see {@code OtpAuthConfig}).
 *
 * <p>REQUEST is limited by (IP, keyed-phone-digest); VERIFY is limited by (IP, opaque challenge id)
 * — never the raw phone, never a global-only IP check (a single IP must not be able to grind through
 * every OTP for one phone; a single phone must not be a channel to grind one challenge).
 */
public class OtpRateLimiter {

    static final String REQUEST_IP_PREFIX = "rl:otp:request:ip:";
    static final String REQUEST_PHONE_PREFIX = "rl:otp:request:phone:";
    static final String VERIFY_IP_PREFIX = "rl:otp:verify:ip:";
    static final String VERIFY_CHALLENGE_PREFIX = "rl:otp:verify:challenge:";

    private final RateLimitStore store;
    private final OtpAuthProperties properties;

    public OtpRateLimiter(RateLimitStore store, OtpAuthProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public Admission admitRequest(String clientIp, String phoneDigest) {
        List<BucketSpec> buckets = List.of(
                new BucketSpec(BucketDimension.IP, REQUEST_IP_PREFIX + clientIp,
                        properties.getRequestIp().getCapacity(), properties.getRequestIp().getRefillPerSecond()),
                new BucketSpec(BucketDimension.PHONE, REQUEST_PHONE_PREFIX + phoneDigest,
                        properties.getRequestPhone().getCapacity(),
                        properties.getRequestPhone().getRefillPerSecond()));
        return store.tryConsume(buckets, 1);
    }

    public Admission admitVerify(String clientIp, String challengeId) {
        List<BucketSpec> buckets = List.of(
                new BucketSpec(BucketDimension.IP, VERIFY_IP_PREFIX + clientIp,
                        properties.getVerifyIp().getCapacity(), properties.getVerifyIp().getRefillPerSecond()),
                new BucketSpec(BucketDimension.CHALLENGE, VERIFY_CHALLENGE_PREFIX + challengeId,
                        properties.getVerifyChallenge().getCapacity(),
                        properties.getVerifyChallenge().getRefillPerSecond()));
        return store.tryConsume(buckets, 1);
    }
}
