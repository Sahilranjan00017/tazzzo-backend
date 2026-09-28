package com.tazzzo.customer.cart;

import com.mongodb.MongoWriteException;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.read.CommerceReadUnavailableException;
import com.tazzzo.commerce.read.CommerceSkuBatchReader;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * PR-12C — cart INTENT orchestration (SKU + desired quantity + version). Never pricing, inventory or
 * routing authority: nothing here reads or stores price/stock/serviceability; response enrichment is
 * {@link CartEnricher}'s job, from CURRENT commerce truth.
 *
 * <p><b>Retention (injected {@link Clock} only):</b> {@code expiresAt = updatedAt + 7 days}; every
 * successful mutation refreshes it. Runtime expiry checks are authoritative (no Mongo TTL). An
 * expired cart behaves as EMPTY, and clearing it ADVANCES the version — the document is never
 * deleted, so a stale client can never re-create over a "version 0" reset.
 *
 * <p><b>Atomicity:</b> every mutation verifies customer identity and applies the change in ONE
 * {@link Tx#call}; the callback contains only transactional Mongo state (no metrics, no logging of
 * success, no network/commerce calls) and returns an immutable {@link CartState} — the response
 * needs no post-commit read and there is no mutable holder. SKU visibility (a commerce read) is
 * checked BEFORE the transaction; Checkout must revalidate anyway.
 *
 * <p><b>Concurrency:</b> a same-version race is a version compare-and-swap inside the transaction:
 * exactly one writer commits; the loser re-reads (driver retry) and gets PRECONDITION_FAILED.
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);
    static final Duration RETENTION = Duration.ofDays(7);

    private final CartRepository carts;
    private final CartLimitProperties limits;
    private final Clock clock;
    private final CartObservability observability;
    private final ObjectProvider<CustomerIdentityAuthority> identityAuthority;
    private final CommerceSkuBatchReader commerce;
    private final Tx tx;

    public CartService(CartRepository carts, CartLimitProperties limits, Clock clock,
                       CartObservability observability, ObjectProvider<CustomerIdentityAuthority> identityAuthority,
                       CommerceSkuBatchReader commerce, Tx tx) {
        this.carts = carts;
        this.limits = limits;
        this.clock = clock;
        this.observability = observability;
        this.identityAuthority = identityAuthority;
        this.commerce = commerce;
        this.tx = tx;
    }

    // ---------- read ----------

    public CartState get(CustomerId customerId) {
        try {
            verifyIdentityExistsNonTransactional(customerId);
            Instant now = clock.instant();
            Document doc = carts.findById(customerId.value());
            if (doc == null) {
                return CartState.empty(0);
            }
            if (isExpired(doc, now) && !items(doc).isEmpty()) {
                return clearExpired(customerId, doc, now);
            }
            return toState(doc, now);
        } catch (CartFailure e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("customer_cart_read_failed type={}", e.getClass().getSimpleName());
            throw new CartFailure(CartFailure.Reason.UNAVAILABLE);
        }
    }

    /** Housekeeping: never fails the read. On a lost race or write outage the cart is reported as
     *  logically empty at its persisted version (a later mutation handles expiry transactionally). */
    private CartState clearExpired(CustomerId customerId, Document seen, Instant now) {
        long seenVersion = version(seen);
        try {
            if (carts.clearExpiredIfVersion(customerId.value(), seenVersion, now, now.plus(RETENTION))) {
                observability.cartExpired();
                return new CartState(seenVersion + 1, List.of(), null, true);
            }
            Document fresh = carts.findById(customerId.value());
            return fresh == null ? CartState.empty(0) : toState(fresh, now);
        } catch (RuntimeException e) {
            log.warn("customer_cart_expiry_clear_failed type={}", e.getClass().getSimpleName());
            return new CartState(seenVersion, List.of(), null, false);
        }
    }

    // ---------- mutations ----------

    public CartState setItem(CustomerId customerId, String skuId, int quantity, long expectedVersion) {
        if (quantity < 1 || quantity > limits.getMaxQuantityPerItem()) {
            throw new CartFailure(CartFailure.Reason.INVALID_REQUEST);
        }
        requireVisibleSku(skuId);
        Instant now = clock.instant();
        return mutate(() -> tx.call(session -> {
            verifyIdentityExistsTransactional(session, customerId);
            Document doc = carts.findById(session, customerId.value());
            requireVersion(doc, expectedVersion);
            boolean expired = doc != null && isExpired(doc, now);
            List<Document> items = liveItems(doc, now);

            Document existing = find(items, skuId);
            if (existing != null) {
                existing.put("quantity", quantity);
                existing.put("updatedAt", Date.from(now));
            } else {
                if (items.size() >= limits.getMaxDistinctItems()) {
                    throw new CartFailure(CartFailure.Reason.CART_ITEM_LIMIT_REACHED);
                }
                items.add(new Document("skuId", skuId).append("quantity", quantity)
                        .append("addedAt", Date.from(now)).append("updatedAt", Date.from(now)));
            }
            return write(session, customerId, doc, expectedVersion, items, now, expired);
        }));
    }

    public CartState removeItem(CustomerId customerId, String skuId, long expectedVersion) {
        Instant now = clock.instant();
        return mutate(() -> tx.call(session -> {
            verifyIdentityExistsTransactional(session, customerId);
            Document doc = carts.findById(session, customerId.value());
            requireVersion(doc, expectedVersion);
            boolean expired = doc != null && isExpired(doc, now);
            List<Document> items = liveItems(doc, now);
            Document existing = find(items, skuId);
            if (existing == null) {
                throw new CartFailure(CartFailure.Reason.NOT_FOUND);
            }
            items.remove(existing);
            return write(session, customerId, doc, expectedVersion, items, now, expired);
        }));
    }

    /** Clearing a never-persisted cart (cart-0) is a deterministic no-op returning the empty v0 cart. */
    public CartState clear(CustomerId customerId, long expectedVersion) {
        Instant now = clock.instant();
        return mutate(() -> tx.call(session -> {
            verifyIdentityExistsTransactional(session, customerId);
            Document doc = carts.findById(session, customerId.value());
            requireVersion(doc, expectedVersion);
            if (doc == null) {
                return CartState.empty(0);
            }
            boolean expired = isExpired(doc, now);
            return write(session, customerId, doc, expectedVersion, new ArrayList<>(), now, expired);
        }));
    }

    private CartState mutate(java.util.function.Supplier<CartState> body) {
        try {
            CartState result = body.get();
            if (result.expiredCleared()) {
                observability.cartExpired(); // only AFTER the transaction committed
            }
            return result;
        } catch (CartFailure e) {
            throw e;
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) {
                // lost a concurrent first-create race on _id: same as any stale-version loser
                throw new CartFailure(CartFailure.Reason.PRECONDITION_FAILED);
            }
            log.error("customer_cart_mutation_failed type={}", e.getClass().getSimpleName());
            throw new CartFailure(CartFailure.Reason.UNAVAILABLE);
        } catch (RuntimeException e) {
            log.error("customer_cart_mutation_failed type={}", e.getClass().getSimpleName());
            throw new CartFailure(CartFailure.Reason.UNAVAILABLE);
        }
    }

    // ---------- transaction-internal helpers (no side effects beyond Mongo) ----------

    private CartState write(com.mongodb.client.ClientSession session, CustomerId customerId, Document doc,
                            long expectedVersion, List<Document> items, Instant now, boolean expired) {
        Instant expiresAt = now.plus(RETENTION);
        long newVersion = expectedVersion + 1;
        if (doc == null) {
            carts.insert(session, new Document("_id", customerId.value()).append("items", items)
                    .append("version", newVersion).append("createdAt", Date.from(now))
                    .append("updatedAt", Date.from(now)).append("expiresAt", Date.from(expiresAt)));
        } else if (!carts.replaceItemsIfVersion(session, customerId.value(), expectedVersion, items, now,
                expiresAt)) {
            throw new CartFailure(CartFailure.Reason.PRECONDITION_FAILED);
        }
        List<CartState.Line> lines = new ArrayList<>(items.size());
        for (Document i : items) {
            lines.add(line(i));
        }
        return new CartState(newVersion, lines, items.isEmpty() ? null : expiresAt, expired);
    }

    private static void requireVersion(Document doc, long expectedVersion) {
        long actual = doc == null ? 0L : version(doc);
        if (actual != expectedVersion) {
            throw new CartFailure(CartFailure.Reason.PRECONDITION_FAILED);
        }
    }

    /** Mutable copy of the live items; an expired cart contributes NONE (behaves as empty). */
    private static List<Document> liveItems(Document doc, Instant now) {
        if (doc == null || isExpired(doc, now)) {
            return new ArrayList<>();
        }
        List<Document> copy = new ArrayList<>();
        for (Document i : items(doc)) {
            copy.add(new Document(i));
        }
        return copy;
    }

    private static Document find(List<Document> items, String skuId) {
        for (Document i : items) {
            if (skuId.equals(i.getString("skuId"))) {
                return i;
            }
        }
        return null;
    }

    // ---------- pre-transaction checks ----------

    private void requireVisibleSku(String skuId) {
        boolean visible;
        try {
            visible = commerce.isCustomerVisible(skuId);
        } catch (CommerceReadUnavailableException e) {
            log.error("customer_cart_sku_check_failed category={}", e.category());
            throw new CartFailure(CartFailure.Reason.UNAVAILABLE);
        }
        if (!visible) {
            throw new CartFailure(CartFailure.Reason.NOT_FOUND);
        }
    }

    private void verifyIdentityExistsNonTransactional(CustomerId customerId) {
        CustomerIdentityAuthority authority = requireAuthority();
        boolean exists;
        try {
            exists = authority.exists(customerId);
        } catch (RuntimeException e) {
            log.error("customer_identity_authority_failed type={}", e.getClass().getSimpleName());
            throw new CartFailure(CartFailure.Reason.UNAVAILABLE);
        }
        if (!exists) {
            throw new CartFailure(CartFailure.Reason.UNAVAILABLE);
        }
    }

    private void verifyIdentityExistsTransactional(com.mongodb.client.ClientSession session, CustomerId customerId) {
        CustomerIdentityAuthority authority = requireAuthority();
        boolean exists;
        try {
            exists = authority.exists(session, customerId);
        } catch (RuntimeException e) {
            log.error("customer_identity_authority_failed type={}", e.getClass().getSimpleName());
            throw new CartFailure(CartFailure.Reason.UNAVAILABLE);
        }
        if (!exists) {
            throw new CartFailure(CartFailure.Reason.UNAVAILABLE);
        }
    }

    private CustomerIdentityAuthority requireAuthority() {
        CustomerIdentityAuthority authority = identityAuthority.getIfAvailable();
        if (authority == null) {
            throw new CartFailure(CartFailure.Reason.UNAVAILABLE);
        }
        return authority;
    }

    // ---------- document mapping ----------

    private static boolean isExpired(Document doc, Instant now) {
        Date expiresAt = doc.getDate("expiresAt");
        return expiresAt != null && !expiresAt.toInstant().isAfter(now);
    }

    private static long version(Document doc) {
        return doc.get("version", Number.class).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Document> items(Document doc) {
        List<Document> items = doc.getList("items", Document.class);
        return items == null ? List.of() : items;
    }

    private static CartState.Line line(Document i) {
        return new CartState.Line(i.getString("skuId"), i.get("quantity", Number.class).intValue(),
                i.getDate("addedAt").toInstant(), i.getDate("updatedAt").toInstant());
    }

    private static CartState toState(Document doc, Instant now) {
        boolean expired = isExpired(doc, now);
        List<CartState.Line> lines = new ArrayList<>();
        if (!expired) {
            for (Document i : items(doc)) {
                lines.add(line(i));
            }
        }
        Instant expiresAt = lines.isEmpty() ? null : doc.getDate("expiresAt").toInstant();
        return new CartState(version(doc), lines, expiresAt, false);
    }
}
