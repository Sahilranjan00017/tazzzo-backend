package com.tazzzo.customer.checkout;

import com.mongodb.MongoWriteException;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.customer.address.AddressId;
import com.tazzzo.customer.address.AddressRepository;
import com.tazzzo.customer.cart.CartEnricher;
import com.tazzzo.customer.cart.CartFailure;
import com.tazzzo.customer.cart.CartIssue;
import com.tazzzo.customer.cart.CartResponseDto;
import com.tazzzo.customer.cart.CartService;
import com.tazzzo.customer.cart.CartState;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * PR-13A — checkout VALIDATION + QUOTE orchestration. Cart is intent; checkout is authoritative
 * validation: every line is revalidated against CURRENT commerce truth, obtained through the SAME
 * seam the cart uses ({@link CartEnricher} → {@code CommerceSkuBatchReader} → the unchanged
 * {@code ProductCardRuntimeEnricher}). Nothing here computes price, stock, serviceability or
 * buyability; this class only demands that the authoritative result is "all lines buyable at a known
 * serviceable address" and snapshots it.
 *
 * <p><b>Not a reservation.</b> A quote reserves NO stock and is not a permanent price lock. It states
 * what was true at {@code createdAt} and is honoured only until {@code expiresAt} (default 5 minutes,
 * injected {@link Clock}, runtime expiry authoritative, no TTL index). Stock or price may change the
 * instant after a quote is created; a future Order MUST revalidate stock and quote validity.
 *
 * <p><b>Sequence:</b> replay lookup → read cart + bind to the client's cart ETag → resolve the OWNED
 * address → authoritative commerce validation (outside any transaction: it does network/read work
 * that cannot join a session) → build an immutable candidate → ONE {@link Tx#call}: identity still
 * exists, idempotency re-check, cart RECHECK (version unchanged and not expired), insert. The
 * callback only touches transactional Mongo state and returns an immutable value; the quote id,
 * digests and candidate are fixed BEFORE the transaction so driver retries are stable. No routing
 * identity is persisted: the enricher deliberately hides the internal fulfillment location and Order
 * must re-resolve it anyway.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final String FINGERPRINT_VERSION = "v1";

    private final CartService carts;
    private final CartEnricher enricher;
    private final AddressRepository addresses;
    private final CheckoutQuoteRepository quotes;
    private final CheckoutProperties properties;
    private final Clock clock;
    private final ObjectProvider<CustomerIdentityAuthority> identityAuthority;
    private final Tx tx;

    public CheckoutService(CartService carts, CartEnricher enricher, AddressRepository addresses,
                           CheckoutQuoteRepository quotes, CheckoutProperties properties, Clock clock,
                           ObjectProvider<CustomerIdentityAuthority> identityAuthority, Tx tx) {
        this.carts = carts;
        this.enricher = enricher;
        this.addresses = addresses;
        this.quotes = quotes;
        this.properties = properties;
        this.clock = clock;
        this.identityAuthority = identityAuthority;
        this.tx = tx;
    }

    static boolean isValidIdempotencyKey(String key) {
        return key != null && IDEMPOTENCY_KEY.matcher(key).matches();
    }

    // ---------- create ----------

    public CheckoutQuote createQuote(CustomerId customerId, long expectedCartVersion, String idempotencyKey,
                                     String addressIdRaw, String requestId) {
        try {
            return doCreate(customerId, expectedCartVersion, idempotencyKey, addressIdRaw, requestId);
        } catch (com.mongodb.MongoException e) {
            // datastore outage == controlled 503; anything ELSE unexpected is a defect and propagates to
            // the boundary as a counted, safe 500 (never disguised as an outage)
            log.error("customer_checkout_create_failed type={}", e.getClass().getSimpleName());
            throw new CheckoutFailure(CheckoutFailure.Reason.UNAVAILABLE);
        }
    }

    private CheckoutQuote doCreate(CustomerId customerId, long expectedCartVersion, String idempotencyKey,
                                   String addressIdRaw, String requestId) {
        if (!isValidIdempotencyKey(idempotencyKey)) {
            throw new CheckoutFailure(CheckoutFailure.Reason.INVALID_REQUEST);
        }
        AddressId addressId = parseAddressId(addressIdRaw);
        String keyDigest = sha256Hex(idempotencyKey);
        String fingerprint = sha256Hex(FINGERPRINT_VERSION + "|" + expectedCartVersion + "|" + addressId.value());

        // 1. Idempotent replay: the ORIGINAL committed quote, never re-priced, expiry never extended.
        Document prior = quotes.findByIdempotency(customerId.value(), keyDigest);
        if (prior != null) {
            return replay(prior, fingerprint);
        }

        // 2. The cart the client reviewed. Reading applies the existing cart expiry semantics; this
        //    never mutates a live cart.
        CartState cart = readCart(customerId);
        if (cart.version() != expectedCartVersion) {
            throw new CheckoutFailure(CheckoutFailure.Reason.PRECONDITION_FAILED);
        }
        if (cart.lines().isEmpty()) {
            throw new CheckoutFailure(CheckoutFailure.Reason.CHECKOUT_CART_EMPTY);
        }

        // 3. The OWNED address → the existing commerce LocationQuery (PIN → serviceability → routing).
        LocationQuery location = resolveLocation(customerId, addressId);

        // 4. Authoritative commerce validation through the cart/commerce seam, then all-or-nothing rules.
        CartResponseDto validated = present(cart, location, requestId);
        requireAllBuyable(validated);

        // 5. Immutable candidate (all ids/instants fixed BEFORE the transaction).
        // millisecond precision == what Mongo stores, so the creating response and every replay are identical
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        CheckoutQuote candidate = candidate(CheckoutQuoteId.generate(), expectedCartVersion, addressId, validated, now);

        // 6. Persist.
        return persist(customerId, expectedCartVersion, candidate, keyDigest, fingerprint);
    }

    private CheckoutQuote persist(CustomerId customerId, long expectedCartVersion, CheckoutQuote candidate,
                                  String keyDigest, String fingerprint) {
        try {
            // Tx.call returns the LAST attempt's immutable value straight from the driver retry loop.
            return tx.call(session -> {
                verifyIdentityExists(session, customerId);
                Document existing = quotes.findByIdempotency(session, customerId.value(), keyDigest);
                if (existing != null) {
                    return replay(existing, fingerprint);
                }
                // Cart RECHECK: the cart must still be exactly the version that was validated and must
                // not have expired meanwhile (expired == logically empty at the same version). The clock is
                // read HERE (persist time), not at validation time; it only feeds a comparison, so a
                // retry re-reading it is harmless.
                CartState current = carts.snapshot(session, customerId, clock.instant());
                if (current.version() != expectedCartVersion) {
                    throw new CheckoutFailure(CheckoutFailure.Reason.PRECONDITION_FAILED);
                }
                if (current.lines().isEmpty()) {
                    throw new CheckoutFailure(CheckoutFailure.Reason.CHECKOUT_CART_EMPTY);
                }
                quotes.insert(session, candidate, customerId.value(), keyDigest, fingerprint);
                return candidate;
            });
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) {
                // lost a concurrent same-key create: resolve to the winner's ONE durable quote
                Document winner = quotes.findByIdempotency(customerId.value(), keyDigest);
                if (winner != null) {
                    return replay(winner, fingerprint);
                }
            }
            throw e;
        }
    }

    private static CheckoutQuote replay(Document stored, String fingerprint) {
        if (!fingerprint.equals(stored.getString("fingerprint"))) {
            throw new CheckoutFailure(CheckoutFailure.Reason.IDEMPOTENCY_CONFLICT);
        }
        return CheckoutQuoteRepository.toQuote(stored);
    }

    // ---------- read ----------

    public CheckoutQuote readQuote(CustomerId customerId, String quoteIdRaw) {
        try {
            if (!CheckoutQuoteId.isValid(quoteIdRaw)) {
                throw new CheckoutFailure(CheckoutFailure.Reason.NOT_FOUND);
            }
            Instant now = clock.instant();
            Document doc = quotes.findOwned(quoteIdRaw, customerId.value());
            if (doc == null) {
                throw new CheckoutFailure(CheckoutFailure.Reason.NOT_FOUND);
            }
            CheckoutQuote quote = CheckoutQuoteRepository.toQuote(doc);
            if (quote.isExpired(now)) {
                throw new CheckoutFailure(CheckoutFailure.Reason.QUOTE_EXPIRED);
            }
            return quote;
        } catch (com.mongodb.MongoException e) {
            log.error("customer_checkout_read_failed type={}", e.getClass().getSimpleName());
            throw new CheckoutFailure(CheckoutFailure.Reason.UNAVAILABLE);
        }
    }

    // ---------- validation ----------

    private CartState readCart(CustomerId customerId) {
        try {
            return carts.get(customerId);
        } catch (CartFailure e) {
            throw new CheckoutFailure(CheckoutFailure.Reason.UNAVAILABLE);
        }
    }

    private CartResponseDto present(CartState cart, LocationQuery location, String requestId) {
        try {
            return enricher.present(cart, location, requestId);
        } catch (CartFailure e) {
            throw new CheckoutFailure(CheckoutFailure.Reason.UNAVAILABLE);
        }
    }

    /** All-or-nothing: layered ON TOP of the authoritative runtime result, never a second algorithm. */
    private static void requireAllBuyable(CartResponseDto validated) {
        boolean unserviceable = false;
        List<CheckoutFailure.ItemRejection> rejections = new ArrayList<>();
        for (CartResponseDto.Item item : validated.items()) {
            if (item.issues().contains(CartIssue.ENRICHMENT_UNAVAILABLE)) {
                // a commerce outage is NEVER disguised as a business rejection
                throw new CheckoutFailure(CheckoutFailure.Reason.UNAVAILABLE);
            }
            if (Boolean.FALSE.equals(item.availability().serviceable())) {
                unserviceable = true;
            }
            for (CartIssue issue : item.issues()) {
                CheckoutItemReason reason = map(issue);
                if (reason != null) {
                    rejections.add(new CheckoutFailure.ItemRejection(item.skuId(), reason));
                }
            }
            if (!item.buyable() && item.issues().stream().noneMatch(i -> map(i) != null)
                    && !Boolean.FALSE.equals(item.availability().serviceable())) {
                rejections.add(new CheckoutFailure.ItemRejection(item.skuId(), CheckoutItemReason.NOT_BUYABLE));
            }
        }
        if (unserviceable) {
            throw new CheckoutFailure(CheckoutFailure.Reason.CHECKOUT_UNSERVICEABLE);
        }
        if (!rejections.isEmpty()) {
            throw new CheckoutFailure(CheckoutFailure.Reason.CHECKOUT_ITEM_UNAVAILABLE, rejections);
        }
    }

    private static CheckoutItemReason map(CartIssue issue) {
        return switch (issue) {
            case PRODUCT_UNAVAILABLE -> CheckoutItemReason.PRODUCT_UNAVAILABLE;
            case PRICE_UNAVAILABLE -> CheckoutItemReason.PRICE_UNAVAILABLE;
            case OUT_OF_STOCK -> CheckoutItemReason.OUT_OF_STOCK;
            case INSUFFICIENT_STOCK -> CheckoutItemReason.INSUFFICIENT_STOCK;
            case STOCK_UNKNOWN -> CheckoutItemReason.STOCK_UNKNOWN;
            // handled as their own outcomes (UNSERVICEABLE) or impossible here (a PIN is always supplied)
            case UNSERVICEABLE, LOCATION_REQUIRED, ENRICHMENT_UNAVAILABLE -> null;
        };
    }

    /** int64 paise; exact arithmetic; every line must carry a current ACTIVE INR price. */
    private CheckoutQuote candidate(CheckoutQuoteId id, long cartVersion, AddressId addressId,
                                    CartResponseDto validated, Instant now) {
        List<CheckoutQuote.Line> lines = new ArrayList<>(validated.items().size());
        long subtotal = 0;
        int itemCount = 0;
        try {
            for (CartResponseDto.Item item : validated.items()) {
                if (item.price() == null || !"INR".equals(item.price().currency()) || item.lineTotalPaise() == null) {
                    throw new CheckoutFailure(CheckoutFailure.Reason.CHECKOUT_ITEM_UNAVAILABLE,
                            List.of(new CheckoutFailure.ItemRejection(item.skuId(),
                                    CheckoutItemReason.PRICE_UNAVAILABLE)));
                }
                long unit = item.price().unitPricePaise();
                long lineTotal = Math.multiplyExact(unit, (long) item.quantity());
                subtotal = Math.addExact(subtotal, lineTotal);
                itemCount = Math.addExact(itemCount, item.quantity());
                lines.add(new CheckoutQuote.Line(item.skuId(), item.quantity(), unit, lineTotal));
            }
        } catch (ArithmeticException e) {
            log.error("customer_checkout_total_overflow");
            throw new CheckoutFailure(CheckoutFailure.Reason.UNAVAILABLE);
        }
        return new CheckoutQuote(id.value(), cartVersion, addressId.value(), List.copyOf(lines), itemCount, subtotal,
                "INR", now, now.plus(Duration.ofSeconds(properties.getQuoteTtlSeconds())));
    }

    // ---------- identity / address ----------

    private static AddressId parseAddressId(String raw) {
        try {
            return new AddressId(raw);
        } catch (IllegalArgumentException e) {
            throw new CheckoutFailure(CheckoutFailure.Reason.NOT_FOUND); // malformed == unknown
        }
    }

    private LocationQuery resolveLocation(CustomerId customerId, AddressId addressId) {
        Document doc = addresses.findOwnedById(customerId.value(), addressId.value());
        if (doc == null) {
            throw new CheckoutFailure(CheckoutFailure.Reason.NOT_FOUND); // foreign == unknown
        }
        return LocationQuery.ofPin(new Pincode(doc.getString("postalCode")));
    }

    private void verifyIdentityExists(com.mongodb.client.ClientSession session, CustomerId customerId) {
        CustomerIdentityAuthority authority = identityAuthority.getIfAvailable();
        boolean exists;
        try {
            exists = authority != null && authority.exists(session, customerId);
        } catch (RuntimeException e) {
            log.error("customer_identity_authority_failed type={}", e.getClass().getSimpleName());
            throw new CheckoutFailure(CheckoutFailure.Reason.UNAVAILABLE);
        }
        if (!exists) {
            throw new CheckoutFailure(CheckoutFailure.Reason.UNAVAILABLE);
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
