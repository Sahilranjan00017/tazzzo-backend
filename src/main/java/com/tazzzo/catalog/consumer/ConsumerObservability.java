package com.tazzzo.catalog.consumer;

import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.ratelimit.BucketObservation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Q5-OBS-1 — the consumer surface's measurements, and the ONLY place a meter is created.
 *
 * <p><b>Cardinality is a contract, not a convention.</b> Every tag value passing through here is
 * from a closed, enumerable set: {@link Route}, {@link Outcome}, {@link ProbeResult}, a bucket
 * dimension, an admission decision. Nothing that identifies a client, a release, a node, a product,
 * a request or a Redis key may become a tag — a raw IP as a tag would turn the metric backend into
 * a per-customer table, and an installation id would make it a tracking system. Tag values are
 * therefore taken from enums, never from request data, and {@link #ALLOWED_TAG_KEYS} is the whole
 * vocabulary.
 *
 * <p><b>Instrumentation is subordinate to the business result.</b> Every recording is wrapped so a
 * registry fault cannot turn a valid consumer request into a 5xx. A metric that breaks the thing
 * it measures is worse than no metric.
 *
 * <p>{@code route} is an enum-style bounded label — {@code root} today. Children, LIST and PDP
 * will add fixed values deliberately; nothing here is ever derived from the request path.
 */
@Component
public class ConsumerObservability {

    private static final Logger log = LoggerFactory.getLogger(ConsumerObservability.class);

    public static final String REQUESTS = "tazzzo.catalog.consumer.requests";
    public static final String REQUEST_DURATION = "tazzzo.catalog.consumer.request.duration";
    public static final String RATE_LIMIT_COST = "tazzzo.catalog.consumer.rate_limit.cost";
    public static final String RATE_LIMIT_REMAINING = "tazzzo.catalog.consumer.rate_limit.remaining";
    public static final String RATE_LIMIT_SATURATION = "tazzzo.catalog.consumer.rate_limit.saturation";
    public static final String PROBE_DURATION = "tazzzo.catalog.consumer.visibility.probe.duration";
    public static final String PROBE_FAILURES = "tazzzo.catalog.consumer.visibility.probe.failures";

    /** The complete tag vocabulary. A guard test asserts no meter carries any other key. */
    public static final Set<String> ALLOWED_TAG_KEYS = Set.of("route", "outcome", "dimension", "decision", "result");

    public enum Route {
        ROOT("root");

        private final String tag;

        Route(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    public enum Outcome {
        SUCCESS("success"), RATE_LIMITED("rate_limited"), UNAVAILABLE("unavailable"), NOT_FOUND("not_found");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    public enum ProbeResult {
        HIT("hit"), MISS("miss"), ERROR("error");

        private final String tag;

        ProbeResult(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private final MeterRegistry registry;

    public ConsumerObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void request(Route route, Outcome outcome, Duration elapsed) {
        safely(() -> {
            Counter.builder(REQUESTS)
                    .tag("route", route.tag()).tag("outcome", outcome.tag())
                    .register(registry).increment();
            Timer.builder(REQUEST_DURATION)
                    .tag("route", route.tag()).tag("outcome", outcome.tag())
                    .register(registry).record(elapsed);
        });
    }

    /** The COMPUTED charge, recorded whatever the verdict — it is the weight the route carried. */
    public void cost(Route route, long units) {
        safely(() -> DistributionSummary.builder(RATE_LIMIT_COST)
                .tag("route", route.tag())
                .register(registry).record(units));
    }

    /**
     * Per-bucket state from the same execution that decided. UNAVAILABLE carries no observations,
     * and none are fabricated for it: a remaining/saturation for a decision that was never made
     * would be a number describing nothing.
     */
    public void admission(Route route, Admission admission) {
        List<BucketObservation> observations;
        String decision;
        if (admission instanceof Admission.Allowed allowed) {
            observations = allowed.observations();
            decision = "allowed";
        } else if (admission instanceof Admission.RateLimited limited) {
            observations = limited.observations();
            decision = "rate_limited";
        } else {
            return;
        }
        safely(() -> {
            for (BucketObservation o : observations) {
                DistributionSummary.builder(RATE_LIMIT_REMAINING)
                        .tag("route", route.tag()).tag("dimension", o.dimension().tag())
                        .tag("decision", decision)
                        .register(registry).record(o.remaining());
                DistributionSummary.builder(RATE_LIMIT_SATURATION)
                        .tag("route", route.tag()).tag("dimension", o.dimension().tag())
                        .tag("decision", decision)
                        .register(registry).record(o.saturation());
            }
        });
    }

    public void probe(Route route, ProbeResult result, Duration elapsed) {
        safely(() -> {
            Timer.builder(PROBE_DURATION)
                    .tag("route", route.tag()).tag("result", result.tag())
                    .register(registry).record(elapsed);
            if (result == ProbeResult.ERROR) {
                Counter.builder(PROBE_FAILURES)
                        .tag("route", route.tag())
                        .register(registry).increment();
            }
        });
    }

    /** Never let measuring break the thing measured. */
    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            log.warn("consumer metric recording failed and was ignored: {}", e.toString());
        }
    }
}
