package com.tazzzo.media;

/**
 * The configured object store could not be reached or refused the request (network, credentials, bucket policy). An
 * outage, distinct from "no such object" (an empty {@code inspect}) and from a client's bad request; the message is the
 * failure's class name only, never a provider message that could carry a key, a host or a credential.
 */
public final class MediaStorageFailure extends RuntimeException {
    public MediaStorageFailure(String type) {
        super(type);
    }
}
