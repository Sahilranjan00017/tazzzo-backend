package com.tazzzo.catalog.ratelimit;

/**
 * The bounded identity of a bucket, safe to use as a metric tag (Q5-OBS-1). The Redis key that
 * realises a bucket — which embeds a raw client IP or installation id — never crosses the store
 * boundary; this enum is what travels upward instead.
 */
public enum BucketDimension {
    IP("ip"),
    INSTALLATION("installation");

    private final String tag;

    BucketDimension(String tag) {
        this.tag = tag;
    }

    /** The exact, bounded tag value. */
    public String tag() {
        return tag;
    }
}
