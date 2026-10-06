package com.tazzzo.catalog.tx;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import org.bson.BsonType;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

/**
 * Legacy offer price roll-up, with the price LEDGER RETAINED (risk R1).
 *
 * <p><b>Invariant: {@code price_events} is append-only history.</b> Nothing in this class deletes, rewrites or
 * expires a ledger row, and nothing else in the application does either (there is no TTL index on the collection). The
 * roll-up is a pure projection: it reads legacy offer events and updates the separate derived collection
 * {@code price_rollups} (min / max / count per product and seller). The only write to a ledger row is the one
 * processing flag {@code rolled=true} on a legacy offer event, which is how "already aggregated" is remembered.
 * (Before R1 a second step, {@code purge()}, deleted every flagged row hourly, destroying price history.)
 *
 * <p><b>Two row shapes live in {@code price_events}</b> and only one is rolled up:
 * <ul>
 *   <li>the <i>legacy offer event</i> written by {@code OffersService}: {@code product_id}, {@code source},
 *       {@code seller}, {@code channel}, an int32 {@code price}, {@code ts};</li>
 *   <li>the <i>paise ledger row</i> written by {@code PricingService}: {@code sku_id}, {@code currency},
 *       {@code selling_price_paise}, {@code mrp_paise}, {@code version}, validity window, {@code source}, {@code ts}
 *       (and an actor when attributed). It has no {@code seller} and no {@code price}: it has nothing to aggregate.</li>
 * </ul>
 * Only a row with a string {@code product_id}, a string {@code seller} and an int32 {@code price} is rolled up. Every
 * other row is neither read for aggregation nor written, so a paise row can never produce a null or invalid aggregate and
 * is never touched at all.
 *
 * <p><b>Progress and idempotency.</b> The {@code rolled} flag is the progress marker (no deletion is needed to avoid
 * re-processing). For each candidate the roll-up first CLAIMS it with a conditional update
 * ({@code rolled != true} to {@code rolled=true}) and aggregates only if it won the claim, all in one transaction. A second
 * run, a restart, or an overlapping run on another instance therefore sees the flag and skips the row: no double count,
 * no duplicate derived row. If anything fails the transaction aborts, so no flag is set, nothing is aggregated, and a
 * retry starts from the same state.
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
     * M3: the aggregation boundary is a DOMAIN decision and lives here, not in the scheduler. The flag protocol
     * already makes a concurrent commit safe (an event committed later is simply not flagged yet and is picked up by the
     * next run), so the boundary is "now".
     */
    public void rollup() {
        rollup(new Date());
    }

    /** Aggregates every not-yet-rolled LEGACY offer event with ts <= upTo into price_rollups and flags it; deletes nothing. */
    public void rollup(Date upTo) {
        tx.run(session -> {
            EventPayload e = new EventPayload("PRICE_ROLLUP", "TZP-SYSTEM", Map.of("upTo", upTo.toString()));
            for (Document ev : writePath.database().getCollection("price_events")
                    .find(session, Filters.and(Filters.lte("ts", upTo), Filters.ne("rolled", true), LEGACY_OFFER_SHAPE))
                    .sort(Sorts.ascending("ts", "_id")).into(new ArrayList<>())) {
                UpdateResult[] claim = new UpdateResult[1];
                writePath.auxWrite(session, "price_events", e, c -> claim[0] = c.updateOne(session,
                        Filters.and(Filters.eq("_id", ev.get("_id")), Filters.ne("rolled", true)),
                        Updates.set("rolled", true)));
                if (claim[0].getModifiedCount() == 0) {
                    continue; // another run already processed this event: never aggregate it twice
                }
                int price = ev.getInteger("price");
                writePath.auxWrite(session, "price_rollups", e, c -> c.updateOne(session,
                        Filters.and(Filters.eq("product_id", ev.getString("product_id")),
                                Filters.eq("seller", ev.getString("seller"))),
                        Updates.combine(
                                Updates.min("min_price", price),
                                Updates.max("max_price", price),
                                Updates.inc("count", 1)),
                        new UpdateOptions().upsert(true)));
            }
        });
    }

    /** The rolled-up shape: a legacy offer event always has a string product id and seller and an int32 price. */
    private static final Bson LEGACY_OFFER_SHAPE = Filters.and(
            Filters.type("product_id", BsonType.STRING),
            Filters.type("seller", BsonType.STRING),
            Filters.type("price", BsonType.INT32));
}
