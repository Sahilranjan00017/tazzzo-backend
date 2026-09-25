package com.tazzzo.catalog.consumer;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * THE TR-4B existence probe for every consumer route: ONE indexed {@code limit(1)} read against
 * CURRENT membership, through the shared {@link ConsumerEligibility} predicate. One implementation,
 * so a node's visibility means the same thing whether ROOT, CHILDREN or LIST asked.
 *
 * <p>An empty vertical set matches nothing, which is correct — no consumer-visible descendant means
 * no eligible product.
 */
@Component
public class ConsumerVisibilityProbe {

    private final MongoDatabase db;
    private final ConsumerObservability observe;

    public ConsumerVisibilityProbe(MongoDatabase db, ConsumerObservability observe) {
        this.db = db;
        this.observe = observe;
    }

    /**
     * @return true when at least one currently eligible product is classified under one of
     *         {@code verticalIds}
     * @throws ConsumerFailures.Unavailable the probe failed. TR-4B failure semantics: a timeout is
     *         NOT evidence that a node is empty. Neither "show it" nor "hide it" is available — the
     *         state is UNKNOWN, so the whole request fails rather than manufacturing an answer.
     */
    public boolean hasEligibleProduct(ConsumerObservability.Route route,
                                      ConsumerObservability.ProbeScope scope,
                                      List<String> verticalIds) {
        long started = System.nanoTime();
        try {
            boolean hit = db.getCollection("products")
                    .find(ConsumerEligibility.within(verticalIds))
                    .projection(new Document("_id", 1))
                    .limit(1)
                    .first() != null;
            observe.probe(route, scope,
                    hit ? ConsumerObservability.ProbeResult.HIT : ConsumerObservability.ProbeResult.MISS,
                    Duration.ofNanos(System.nanoTime() - started));
            return hit;
        } catch (RuntimeException e) {
            observe.probe(route, scope, ConsumerObservability.ProbeResult.ERROR,
                    Duration.ofNanos(System.nanoTime() - started));
            throw new ConsumerFailures.Unavailable("visibility probe failed");
        }
    }
}
