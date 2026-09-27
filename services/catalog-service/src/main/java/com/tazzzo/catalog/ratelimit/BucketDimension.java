package com.tazzzo.catalog.ratelimit;

/**
 * The bounded identity of a bucket, safe to use as a metric tag (Q5-OBS-1). The Redis key that
 * realises a bucket — which embeds a raw client IP, installation id, or (PR-11B) a keyed phone
 * digest / opaque challenge id — never crosses the store boundary; this enum is what travels
 * upward instead.
 */
public enum BucketDimension {
    IP("ip"),
    INSTALLATION("installation"),
    /** PR-11B: keyed by {@code OtpVerifierCodec.phoneBucketDigest} — never the raw phone. */
    PHONE("phone"),
    /** PR-11B: keyed by the opaque {@code OTP_*} challenge id — carries no PII itself. */
    CHALLENGE("challenge");

    private final String tag;

    BucketDimension(String tag) {
        this.tag = tag;
    }

    /** The exact, bounded tag value. */
    public String tag() {
        return tag;
    }
}
