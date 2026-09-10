package com.tazzzo.catalog.ratelimit;

/**
 * The client IP could not be resolved (Q5-IP-2a). Raised when a TRUSTED proxy sends no
 * {@code X-Forwarded-For}, sends a malformed one, or sends a chain containing no client address.
 *
 * <p><b>Fails closed on purpose.</b> The alternative — falling back to the proxy's own address —
 * would put every client behind that proxy into ONE bucket, so the limiter would either throttle
 * the entire customer base at once or be effectively disabled. An unresolvable identity is an
 * infrastructure fault, not a rate decision, and the consumer surface answers it as such
 * (Q5-FAIL-1: 503, never 429).
 */
public class ClientIpUnresolvableException extends RuntimeException {

    public ClientIpUnresolvableException(String message) {
        super(message);
    }
}
