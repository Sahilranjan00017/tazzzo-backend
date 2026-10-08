package com.tazzzo.catalog.consumer;

import java.util.Optional;

/**
 * The two Q5 admission dimensions of one consumer request (Q5-IP-2a, Q5-e): the resolved client
 * IP and, when the client sent one, its installation id. Resolved once in the controller and
 * carried to the admission gate unchanged — no route re-derives identity.
 *
 * <p>{@code trustedCaller} is present only when {@link com.tazzzo.catalog.ratelimit.TrustedCallerResolver}
 * verified a trusted server-side caller's credential; the request is then charged to that caller's own
 * bucket instead of the IP/installation buckets.
 */
public record ConsumerIdentity(String clientIp, Optional<String> installationId, Optional<String> trustedCaller) {

    /** An ordinary (untrusted) client: admitted by IP and, when present, installation id. */
    public ConsumerIdentity(String clientIp, Optional<String> installationId) {
        this(clientIp, installationId, Optional.empty());
    }
}
