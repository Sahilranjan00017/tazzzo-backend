package com.tazzzo.catalog.consumer;

import java.util.Optional;

/**
 * The two Q5 admission dimensions of one consumer request (Q5-IP-2a, Q5-e): the resolved client
 * IP and, when the client sent one, its installation id. Resolved once in the controller and
 * carried to the admission gate unchanged — no route re-derives identity.
 */
public record ConsumerIdentity(String clientIp, Optional<String> installationId) {
}
