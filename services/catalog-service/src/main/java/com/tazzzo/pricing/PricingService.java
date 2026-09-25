package com.tazzzo.pricing;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.money.Currency;
import com.tazzzo.common.money.Money;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Production Pricing foundation (PR-03). Owns the canonical current price SoT ({@code price_current})
 * and the paise price ledger ({@code price_events}). It does NOT own or touch the legacy
 * {@code offers_current}/{@code price_events.price} raw commercial input (OffersService), whose unit
 * is legacy-unknown and never converted.
 *
 * <p><b>Reuse, not reinvention.</b> Writes go through the existing {@link Tx} transaction wrapper and
 * {@link WritePath} (event-before-state, C-3/C-4) — the price ledger row is appended before the
 * canonical state, in one transaction. No second transaction framework is introduced.
 *
 * <p><b>Plain class by design.</b> No Spring stereotype: PR-03 exposes no endpoint, so nothing wires
 * it yet. The Commerce Read PR that first needs a bean will add component scanning for
 * {@code com.tazzzo.pricing} (a one-line additive change) — deliberately not done here.
 *
 * <p><b>Observability hook points</b> (STEP 18; metrics to be attached when the exporter is chosen):
 * price_write_success, price_write_validation_failure, price_write_conflict, price_read_missing,
 * price_read_expired. Logged as skuId+outcome+version only — never the full command payload.
 */
public class PricingService implements PriceReadPort {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);
    static final String CURRENT = "price_current";
    static final String LEDGER = "price_events";

    private final Tx tx;
    private final WritePath writePath;
    private final MongoDatabase db;
    private final Clock clock;

    public PricingService(Tx tx, WritePath writePath, Clock clock) {
        this.tx = Objects.requireNonNull(tx);
        this.writePath = Objects.requireNonNull(writePath);
        this.db = writePath.database();
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Upsert the canonical current price for a SKU. Validates invariants, appends an immutable
     * paise ledger event, then applies the current-price state under optimistic concurrency —
     * atomically. A stale {@code expectedVersion} yields {@link PriceConflictException}; the whole
     * transaction (including the ledger row) rolls back.
     *
     * @return the new version after this write.
     */
    public long upsertPrice(UpsertPriceCommand cmd) {
        validateCommand(cmd);
        long newVersion = (cmd.expectedVersion() == null) ? 1L : cmd.expectedVersion() + 1;
        String skuId = cmd.skuId();
        Instant nowInstant = clock.instant();
        Date now = Date.from(nowInstant);
        Date from = cmd.effectiveFrom() == null ? null : Date.from(cmd.effectiveFrom());
        Date to = cmd.effectiveTo() == null ? null : Date.from(cmd.effectiveTo());

        EventPayload event = new EventPayload("PRICE_UPDATED", skuId, auditDetail(cmd, newVersion));

        try {
            tx.run(session -> {
                // 1) immutable paise ledger row (the "event" in event-before-state). Legacy rows
                //    (product_id/source/seller/channel/price) are a DIFFERENT shape and untouched;
                //    new rows are additive and carry only explicit paise fields + version.
                writePath.auxWrite(session, LEDGER, event, c -> c.insertOne(session, ledgerRow(cmd, newVersion, now)));

                // 2) canonical current-price state, CAS on version, same transaction.
                if (cmd.expectedVersion() == null) {
                    writePath.auxWrite(session, CURRENT, event, c ->
                            c.insertOne(session, currentDoc(cmd, newVersion, now, from, to)));
                } else {
                    UpdateResult[] r = new UpdateResult[1];
                    writePath.auxWrite(session, CURRENT, event, c -> r[0] = c.updateOne(session,
                            Filters.and(Filters.eq("sku_id", skuId),
                                    Filters.eq("currency", cmd.currency().name()),
                                    Filters.eq("version", cmd.expectedVersion())),
                            Updates.combine(
                                    Updates.set("selling_price_paise", cmd.sellingPricePaise()),
                                    Updates.set("mrp_paise", cmd.mrpPaise()),
                                    Updates.set("version", newVersion),
                                    Updates.set("active", true),
                                    Updates.set("effective_from", from),
                                    Updates.set("effective_to", to),
                                    Updates.set("source", cmd.source()),
                                    Updates.set("updated_at", now))));
                    if (r[0].getModifiedCount() == 0) {
                        // aborts the transaction -> ledger row rolled back too
                        throw new PriceConflictException("stale update for " + skuId
                                + " expectedVersion=" + cmd.expectedVersion());
                    }
                }
            });
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == com.mongodb.ErrorCategory.DUPLICATE_KEY) {
                log.info("price_write_conflict sku={} reason=duplicate_create", skuId);
                throw new PriceConflictException("price already exists for " + skuId
                        + "; use expectedVersion to update");
            }
            throw e;
        }
        log.info("price_write_success sku={} version={}", skuId, newVersion);
        return newVersion;
    }

    @Override
    public PriceLookup findCurrentPrice(String skuId) {
        Document d = db.getCollection(CURRENT).find(
                Filters.and(Filters.eq("sku_id", skuId), Filters.eq("currency", Currency.INR.name()))).first();
        if (d == null) {
            log.debug("price_read_missing sku={}", skuId);
            return PriceLookup.missing();
        }
        Price price = new Price(skuId, Currency.INR,
                asLong(d.get("selling_price_paise")), asLong(d.get("mrp_paise")),
                asLong(d.get("version")), d.getBoolean("active", false),
                toInstant(d.getDate("effective_from")), toInstant(d.getDate("effective_to")));
        PriceStatus status = price.statusAt(clock.instant());
        if (status == PriceStatus.EXPIRED) {
            log.debug("price_read_expired sku={} version={}", skuId, price.version());
        }
        return PriceLookup.of(status, price);
    }

    // --- validation (STEP 13) -------------------------------------------------

    static Price validateCommand(UpsertPriceCommand cmd) {
        try {
            Objects.requireNonNull(cmd, "command required");
            if (cmd.skuId() == null || cmd.skuId().isBlank()) {
                throw new InvalidPriceException("skuId required");
            }
            if (cmd.currency() != Currency.INR) {
                throw new InvalidPriceException("unsupported currency: " + cmd.currency());
            }
            if (cmd.expectedVersion() != null && cmd.expectedVersion() < 1) {
                throw new InvalidPriceException("expectedVersion must be positive: " + cmd.expectedVersion());
            }
            long version = (cmd.expectedVersion() == null) ? 1L : cmd.expectedVersion() + 1;
            // Price's compact constructor enforces: non-negative paise (via Money),
            // mrp >= selling, positive version, effectiveTo > effectiveFrom.
            return new Price(cmd.skuId(), cmd.currency(), cmd.sellingPricePaise(), cmd.mrpPaise(),
                    version, true, cmd.effectiveFrom(), cmd.effectiveTo());
        } catch (InvalidPriceException e) {
            log.info("price_write_validation_failure sku={} reason={}", safeSku(cmd), e.getMessage());
            throw e;
        } catch (IllegalArgumentException | NullPointerException e) {
            log.info("price_write_validation_failure sku={} reason={}", safeSku(cmd), e.getMessage());
            throw new InvalidPriceException(e.getMessage());
        }
    }

    // --- document builders ----------------------------------------------------

    private Document currentDoc(UpsertPriceCommand cmd, long version, Date now, Date from, Date to) {
        return new Document("sku_id", cmd.skuId())
                .append("currency", cmd.currency().name())
                .append("selling_price_paise", cmd.sellingPricePaise())
                .append("mrp_paise", cmd.mrpPaise())
                .append("version", version)
                .append("active", true)
                .append("effective_from", from)
                .append("effective_to", to)
                .append("source", cmd.source())
                .append("created_at", now)
                .append("updated_at", now);
    }

    private Document ledgerRow(UpsertPriceCommand cmd, long version, Date now) {
        // product_id == sku_id so the existing (product_id, ts) index also serves new rows and
        // legacy readers keep working; explicit paise fields distinguish new rows from legacy ones.
        return new Document("product_id", cmd.skuId())
                .append("sku_id", cmd.skuId())
                .append("currency", cmd.currency().name())
                .append("selling_price_paise", cmd.sellingPricePaise())
                .append("mrp_paise", cmd.mrpPaise())
                .append("version", version)
                .append("effective_from", cmd.effectiveFrom() == null ? null : Date.from(cmd.effectiveFrom()))
                .append("effective_to", cmd.effectiveTo() == null ? null : Date.from(cmd.effectiveTo()))
                .append("source", cmd.source())
                .append("ts", now);
    }

    private Map<String, Object> auditDetail(UpsertPriceCommand cmd, long version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku_id", cmd.skuId());
        m.put("currency", cmd.currency().name());
        m.put("selling_price_paise", cmd.sellingPricePaise());
        m.put("mrp_paise", cmd.mrpPaise());
        m.put("version", version);
        return m;
    }

    private static long asLong(Object v) {
        return ((Number) Objects.requireNonNull(v, "numeric field missing")).longValue();
    }

    private static Instant toInstant(Date d) {
        return d == null ? null : d.toInstant();
    }

    private static String safeSku(UpsertPriceCommand cmd) {
        return cmd == null ? "?" : String.valueOf(cmd.skuId());
    }
}
