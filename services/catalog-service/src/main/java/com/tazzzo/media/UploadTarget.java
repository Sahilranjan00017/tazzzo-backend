package com.tazzzo.media;

import java.time.Instant;
import java.util.Map;

/** A short-lived, single-object, direct-to-storage upload instruction (e.g. a presigned PUT). */
public record UploadTarget(String method, String url, Map<String, String> headers, Instant expiresAt) {

    public UploadTarget {
        headers = Map.copyOf(headers);
    }
}
