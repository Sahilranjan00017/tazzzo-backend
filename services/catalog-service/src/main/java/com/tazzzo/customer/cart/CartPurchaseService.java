package com.tazzzo.customer.cart;

import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * PR-15A-0 — {@link CartPurchasePort} over {@link CartRepository}. Session-aware ONLY: it never
 * opens a transaction, verifies identity (the caller's own transaction already does) or records a
 * metric, because it cannot know whether its caller's transaction will commit.
 *
 * <p><b>Clock authority:</b> {@code now} is read from this class's own {@link Clock} inside the call,
 * fresh on every invocation (including a caller's transaction retry) — a cleared cart's
 * {@code updatedAt}/{@code expiresAt} follow the same retention rule as every other cart write
 * ({@link CartService#RETENTION}).
 *
 * <p><b>Why read-then-guarded-write is race-safe:</b> both run in the caller's snapshot-isolated
 * transaction. A concurrent cart edit that commits after this snapshot makes the guarded write raise
 * a write conflict, the driver retries the caller's whole callback, and the retry re-reads the newer
 * version and takes the {@link CartPurchaseOutcome#NEWER_CART_PRESERVED} branch. The clear itself is
 * filtered on {@code {_id, version}} exactly like every other version-guarded cart write.
 */
@Service
public class CartPurchaseService implements CartPurchasePort {

    static final String MARKER = "purchasedThroughVersion";

    private final CartRepository carts;
    private final Clock clock;

    public CartPurchaseService(CartRepository carts, Clock clock) {
        this.carts = carts;
        this.clock = clock;
    }

    @Override
    public boolean isSourceVersionPurchased(ClientSession session, CustomerId customerId, long sourceCartVersion) {
        requireValidSourceVersion(sourceCartVersion);
        Document doc = carts.findById(session, customerId.value());
        return doc != null && purchasedThrough(doc) >= sourceCartVersion;
    }

    @Override
    public CartPurchaseOutcome finalizePurchase(ClientSession session, CustomerId customerId,
                                                long sourceCartVersion) {
        requireValidSourceVersion(sourceCartVersion);
        Document doc = carts.findById(session, customerId.value());
        if (doc == null) {
            throw new CartPurchaseIntegrityException("no cart document for a purchased quote");
        }
        purchasedThrough(doc); // fail loud on a corrupt marker before writing anything
        long live = doc.get("version", Number.class).longValue();

        if (live < sourceCartVersion) {
            throw new CartPurchaseIntegrityException(
                    "live cart version " + live + " is below purchased source version " + sourceCartVersion);
        }
        if (live == sourceCartVersion) {
            Instant now = clock.instant();
            if (!carts.clearPurchasedIfVersion(session, customerId.value(), sourceCartVersion, now,
                    now.plus(CartService.RETENTION))) {
                // The read above and this write share one snapshot: a concurrent change surfaces as a
                // write conflict (driver retry), never as a silent zero-match.
                throw new CartPurchaseIntegrityException("cart changed unexpectedly during purchase finalization");
            }
            return CartPurchaseOutcome.CLEARED;
        }
        // live > source: the customer edited the cart after quoting. Never touch their newer content.
        if (!carts.markPurchasedThrough(session, customerId.value(), sourceCartVersion)) {
            throw new CartPurchaseIntegrityException("cart vanished during purchase finalization");
        }
        return CartPurchaseOutcome.NEWER_CART_PRESERVED;
    }

    /** Absent means 0 (carts predate this field). Present-but-invalid is corruption and fails loud. */
    static long purchasedThrough(Document doc) {
        Object raw = doc.get(MARKER);
        if (raw == null) {
            return 0L;
        }
        if (!(raw instanceof Number n) || n.longValue() < 0) {
            throw new CartPurchaseIntegrityException("invalid " + MARKER + " on cart document");
        }
        return n.longValue();
    }

    private static void requireValidSourceVersion(long sourceCartVersion) {
        if (sourceCartVersion < 1) {
            throw new IllegalArgumentException("sourceCartVersion must be >= 1: " + sourceCartVersion);
        }
    }
}
