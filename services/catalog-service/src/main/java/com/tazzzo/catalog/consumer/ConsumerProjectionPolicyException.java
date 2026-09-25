package com.tazzzo.catalog.consumer;

/**
 * A stored {@code consumer_projection_policy} document is malformed (RESP-PROJ RP-6d).
 *
 * <p>This is an INTERNAL CONFIGURATION FAILURE, and it is deliberately not survivable. It must
 * never degrade into "no policy" and must never be partially repaired, because either would erase
 * the RP-6b distinction the null version exists to carry: {@code projection_version = null} means
 * exactly <em>no policy is authored</em>. A malformed policy that projected some attributes with a
 * null version would make an authoring fault indistinguishable from an unauthored vertical.
 *
 * <p>Consumer transport must render it as a server fault (ERR-1 5xx), never as a 404.
 */
public class ConsumerProjectionPolicyException extends RuntimeException {

    public ConsumerProjectionPolicyException(String message) {
        super(message);
    }
}
