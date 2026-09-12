package com.tazzzo.catalog.consumer;

import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * THE Q5 admission seam for every consumer route. One implementation, so ROOT, CHILDREN and LIST
 * cannot drift in how they charge, how they observe the verdict, or what a missing limiter means.
 *
 * <p>Q5-f: the weight is COMPUTED by the caller from the resolved snapshot or the effective page
 * size, never a constant here. The 294 units a root listing costs on the seeded tree is an
 * OBSERVATION of that tree, not the formula.
 */
@Component
public class ConsumerAdmissionGate {

    private final ObjectProvider<ConsumerRateLimiter> limiter;
    private final ConsumerObservability observe;

    public ConsumerAdmissionGate(ObjectProvider<ConsumerRateLimiter> limiter,
                                 ConsumerObservability observe) {
        this.limiter = limiter;
        this.observe = observe;
    }

    /**
     * Charges {@code units} to every applicable bucket atomically, BEFORE the caller does any live
     * product work.
     *
     * @throws ConsumerFailures.RateLimited an actual bucket denial (429 + Retry-After)
     * @throws ConsumerFailures.Unavailable no limiter is configured (DISABLED mode is fail-closed,
     *         not "unlimited" — Q4-d), or the store could not decide (Q5-FAIL-1: 503, never 429)
     */
    public void charge(ConsumerObservability.Route route, ConsumerIdentity identity, long units) {
        ConsumerRateLimiter rateLimiter = limiter.getIfAvailable();
        if (rateLimiter == null) {
            // DISABLED mode builds no limiter. That is the fail-closed state, not "unlimited":
            // the surface must not serve while it cannot be limited (Q4-d).
            throw new ConsumerFailures.Unavailable("consumer rate limiting is not configured");
        }
        int cost = (int) Math.min(units, Integer.MAX_VALUE);
        observe.cost(route, cost);
        Admission admission = rateLimiter.admit(identity.clientIp(), identity.installationId(), cost);
        observe.admission(route, admission);
        if (admission instanceof Admission.RateLimited limited) {
            throw new ConsumerFailures.RateLimited(limited.retryAfter());
        }
        if (admission instanceof Admission.Unavailable unavailable) {
            throw new ConsumerFailures.Unavailable("limiter: " + unavailable.reason());
        }
    }
}
