package com.tazzzo.catalog.tx;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

/**
 * M9: NO blind TTL, and (post-review) NO purge-by-timestamp either. rollup() aggregates each
 * event exactly once and durably marks it rolled IN THE SAME TXN; purge() deletes ONLY
 * rolled events. This makes rollup idempotent (no double-counting on rerun) and closes the
 * watermark race: an event committed concurrently with an old ts is simply not rolled yet,
 * so it can never be purged un-aggregated. K-2 proves the fail-closed side.
 */
@Service
public class RollupService {

    private final Tx tx;
    private final WritePath writePath;

    public RollupService(Tx tx, WritePath writePath) {
        this.tx = tx;
        this.writePath = writePath;
    }

    /**
     * M3: the aggregation boundary is a DOMAIN decision and lives here, not in the scheduler.
     * The rolled-flag protocol already makes a concurrent commit safe (it is simply not rolled
     * yet, so it can never be purged un-aggregated), so the boundary is "now".
     */
    public void rollup() {
        rollup(new Date());
    }

    /** Aggregate all not-yet-rolled events with ts <= upTo; mark each rolled, same txn. */
    public void rollup(Date upTo) {
        tx.run(session -> {
            EventPayload e = new EventPayload("PRICE_ROLLUP", "TZP-SYSTEM", Map.of("upTo", upTo.toString()));
            for (Document ev : writePath.database().getCollection("price_events")
                    .find(session, Filters.and(Filters.lte("ts", upTo),
                            Filters.ne("rolled", true))).into(new ArrayList<>())) {
                writePath.auxWrite(session, "price_rollups", e, c -> c.updateOne(session,
                        Filters.and(Filters.eq("product_id", ev.getString("product_id")),
                                Filters.eq("seller", ev.getString("seller"))),
                        Updates.combine(
                                Updates.min("min_price", ev.getInteger("price")),
                                Updates.max("max_price", ev.getInteger("price")),
                                Updates.inc("count", 1)),
                        new UpdateOptions().upsert(true)));
                writePath.auxWrite(session, "price_events", e, c -> c.updateOne(session,
                        Filters.eq("_id", ev.get("_id")), Updates.set("rolled", true)));
            }
        });
    }

    /** Deletes ONLY events durably marked rolled. No rolled events -> deletes nothing. */
    public long purge() {
        long[] deleted = new long[1];
        tx.run(session -> writePath.auxWrite(session, "price_events",
                new EventPayload("PRICE_PURGE", "TZP-SYSTEM", Map.of()),
                c -> deleted[0] = c.deleteMany(session, Filters.eq("rolled", true)).getDeletedCount()));
        return deleted[0];
    }
}
