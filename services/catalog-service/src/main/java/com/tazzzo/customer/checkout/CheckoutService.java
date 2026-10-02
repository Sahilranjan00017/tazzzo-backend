package com.tazzzo.customer.checkout;

import com.tazzzo.benefits.BenefitEvaluation;
import com.tazzzo.benefits.BenefitsEvaluationPort;
import com.tazzzo.benefits.BenefitsFailure;
import com.tazzzo.common.money.Money;
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
 * address (capturing its CURRENT version) → authoritative commerce validation (outside any
 * transaction: it does network/read work that cannot join a session) → build an immutable candidate →
 * ONE {@link Tx#call}: identity still exists → idempotency re-check (fingerprint, then EXPIRY — an
 * expired prior quote is never silently re-served as 200, it is {@code QUOTE_EXPIRED}; the caller
 * needs a new Idempotency-Key) → cart RECHECK (version unchanged and not expired) → address RECHECK
 * (same id AND same version, so the quote corresponds to the address state actually validated, not
 * merely to an id that still happens to exist) → insert. The callback only touches transactional
 * Mongo state and returns an immutable value; the quote id, digests and candidate are fixed BEFORE
 * the transaction so driver retries are stable. No routing identity is persisted: the enricher
 * deliberately hides the internal fulfillment location and Order must re-resolve it anyway.
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
    private final BenefitsEvaluationPort benefits;
    private final Tx tx;

    public CheckoutService(CartService carts, CartEnricher enricher, AddressRepository addresses,
                           CheckoutQuoteRepository quotes, CheckoutProperties properties, Clock clock,
                           ObjectProvider<CustomerIdentityAuthority> identityAuthority,
                           BenefitsEvaluationPort benefits, Tx tx) {
        this.carts = carts;
        this.enricher = enricher;
        this.addresses = addresses;
        this.quotes = quotes;
        this.properties = properties;
        this.clock = clock;
        this.identityAuthority = identityAuthority;
        this.benefits = benefits;
        this.tx = tx;
    }

    static boolean isValidIdempotencyKey(String key) {
        return key != null && IDEMPOTENCY_KEY.matcher(key).matches();
    }

    /** The owned address resolved at validation time, WITH the version validated against. */
    private record ValidatedAddress(AddressId id, long version, LocationQuery location) {
    }

    // ---------- create ----------

    public CheckoutQuote createQuote(CustomerId customerId, long expectedCartVersion, String idempotencyKey,
                                     String addressIdRaw, String requestId) {
        try {
            return doCreate(customerId, expectedCartVersion, idempotencyKey, addressIdRaw, requestId);
        } catch (com.mongodb.MongoException e) {
            // datastore outage == controlled 503. A programming/data-integrity defect (an invariant
            // violation reconstructing a persisted quote, a NullPointerException, ...) is NEITHER a
            // MongoException NOR a CheckoutFailure and is deliberately left to propagate UNCAUGHT here,
            // to the controller's catch-all -> 500 INTERNAL, counted once as reason=internal — never
            // disguised as a transient dependency outage.
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
        // ONE semantic "now" governs every expiry judgment this request makes (both replay paths and
        // the freshly-created candidate's clock read), so a single HTTP request never straddles two
        // different notions of "current time".
        Instant now = clock.instant();

        // 1. Idempotent replay: the ORIGINAL committed quote if still ACTIVE, never re-priced, expiry
        //    never extended. If it exists but has EXPIRED, this is 410 QUOTE_EXPIRED, not 200 — a new
        //    quote after expiry needs a NEW Idempotency-Key.
        Document prior = quotes.findByIdempotency(customerId.value(), keyDigest);
        if (prior != null) {
            return replay(prior, fingerprint, now);
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

        // 3. The OWNED address, AND the version it was validated at → the existing commerce
        //    LocationQuery (PIN → serviceability → routing).
        ValidatedAddress address = resolveAddress(customerId, addressId);

        // 4. Authoritative commerce validation through the cart/commerce seam, then all-or-nothing rules.
        CartResponseDto validated = present(cart, address.location(), requestId);
        requireAllBuyable(validated);

        // 5. Immutable candidate (all ids/instants fixed BEFORE the transaction).
        // millisecond precision == what Mongo stores, so the creating response and every replay are identical
        Instant createdAt = now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        // The ADVISORY Benefits snapshot is evaluated over the FINAL canonical merchandise subtotal (standalone port,
        // outside any transaction, like the commerce validation above) and the quote is built ONCE with it. Nothing
        // after this alters the lines, quantities or subtotal: the quote is persisted exactly as built.
        CheckoutQuote candidate = candidate(CheckoutQuoteId.generate(), expectedCartVersion, addressId,
                address.version(), validated, createdAt, subtotal -> evaluateBenefits(customerId, subtotal));

        // 6. Persist.
        return persist(customerId, expectedCartVersion, address, candidate, keyDigest, fingerprint);
    }

    /**
     * Checkout only PROJECTS the Benefits result (no arithmetic, no rule access). A normal no-benefit outcome is a
     * successful quote. FAIL CLOSED: a Benefits outage fails the quote with {@code UNAVAILABLE}; an integrity or
     * programming defect (Benefits {@code INTEGRITY_FAILURE}/{@code INVALID_REQUEST}, or a result inconsistent with the
     * quote subtotal) propagates UNCAUGHT as the existing integrity-defect convention -> a safe 500 {@code INTERNAL}.
     * None of them is ever turned into "no benefit".
     */
    private CheckoutBenefitSnapshot evaluateBenefits(CustomerId customerId, long subtotalPaise) {
        BenefitEvaluation evaluation;
        try {
            evaluation = benefits.evaluate(customerId, Money.ofInrPaise(subtotalPaise));
        } catch (BenefitsFailure e) {
            if (e.reason() == BenefitsFailure.Reason.UNAVAILABLE) {
                log.error("customer_checkout_benefits_unavailable");
                throw new CheckoutFailure(CheckoutFailure.Reason.UNAVAILABLE);
            }
            log.error("customer_checkout_benefits_failed reason={}", e.reason());
            throw new IllegalStateException("benefits evaluation failed");
        }
        try {
            return CheckoutBenefitSnapshot.from(evaluation, subtotalPaise);
        } catch (IllegalArgumentException e) {
            log.error("customer_checkout_benefits_inconsistent");
            throw new IllegalStateException("benefits evaluation is inconsistent with the quote");
        }
    }

    private CheckoutQuote persist(CustomerId customerId, long expectedCartVersion, ValidatedAddress address,
                                  CheckoutQuote candidate, String keyDigest, String fingerprint) {
        try {
            // Tx.call returns the LAST attempt's immutable value straight from the driver retry loop.
            return tx.call(session -> {
                // 1. customer identity still exists
                verifyIdentityExists(session, customerId);

                // 2/3. idempotency existing-row check, including EXPIRY (never silently 200 a stale quote)
                Document existing = quotes.findByIdempotency(session, customerId.value(), keyDigest);
                if (existing != null) {
                    return replay(existing, fingerprint, clock.instant());
                }

                // 4. cart RECHECK: still exactly the validated version, and not expired meanwhile
                //    (expired == logically empty at the same version).
                CartState current = carts.snapshot(session, customerId, clock.instant());
                if (current.version() != expectedCartVersion) {
                    throw new CheckoutFailure(CheckoutFailure.Reason.PRECONDITION_FAILED);
                }
                if (current.lines().isEmpty()) {
                    throw new CheckoutFailure(CheckoutFailure.Reason.CHECKOUT_CART_EMPTY);
                }

                // 5. address RECHECK: ownership/existence/referential integrity ONLY (never
                //    serviceability — that stays outside the transaction) — the SAME id AND the SAME
                //    version that was actually validated, so the quote corresponds to the address
                //    state commerce validation ran against, not merely to an id that still exists.
                Document currentAddress = addresses.findOwnedById(session, customerId.value(), address.id().value());
                if (currentAddress == null || currentAddress.get("version", Number.class).longValue()
                        != address.version()) {
                    throw new CheckoutFailure(CheckoutFailure.Reason.NOT_FOUND);
                }

                // 6. insert
                quotes.insert(session, candidate, customerId.value(), keyDigest, fingerprint);
                return candidate;
            });
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) {
                // lost a concurrent same-key create: resolve to the winner's ONE durable quote,
                // respecting the SAME active/expired semantics as any other replay
                Document winner = quotes.findByIdempotency(customerId.value(), keyDigest);
                if (winner != null) {
                    return replay(winner, fingerprint, clock.instant());
                }
            }
            throw e;
        }
    }

    /**
     * A persisted quote found under this idempotency key. Fingerprint mismatch is a semantic
     * conflict (409); a fingerprint match but expired is 410 (never silently re-served as 200); an
     * active match is the ORIGINAL quote, unchanged. {@link CheckoutQuoteRepository#toQuote} may
     * throw {@link IllegalArgumentException}/{@link ArithmeticException} on a corrupt stored document
     * — deliberately NOT caught here: it propagates as an uncounted data-integrity defect to the
     * public boundary's catch-all (500 INTERNAL), never disguised as a business outcome.
     */
    private static CheckoutQuote replay(Document stored, String fingerprint, Instant now) {
        if (!fingerprint.equals(stored.getString("fingerprint"))) {
            throw new CheckoutFailure(CheckoutFailure.Reason.IDEMPOTENCY_CONFLICT);
        }
        CheckoutQuote quote = CheckoutQuoteRepository.toQuote(stored);
        if (quote.isExpired(now)) {
            throw new CheckoutFailure(CheckoutFailure.Reason.QUOTE_EXPIRED);
        }
        return quote;
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
            // toQuote may throw IllegalArgumentException/ArithmeticException on corrupt data; NOT
            // caught here either, for the identical reason as replay() above.
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

    /**
     * int64 paise; exact arithmetic; every line must carry a current ACTIVE INR price.
     *
     * <p>PR-13B — {@code addressVersion} is the EXACT version already captured by
     * {@link #resolveAddress} (the version commerce validation actually ran against); never re-read
     * here, never derived from anything client-supplied.
     */
    private CheckoutQuote candidate(CheckoutQuoteId id, long cartVersion, AddressId addressId, long addressVersion,
                                    CartResponseDto validated, Instant now,
                                    java.util.function.LongFunction<CheckoutBenefitSnapshot> benefitsForSubtotal) {
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
        CheckoutBenefitSnapshot benefitSnapshot = benefitsForSubtotal.apply(subtotal);
        // advisory money from the canonical subtotal and the STORED-to-be Benefits discount: no second Benefits call,
        // no rate recomputation; built before the single persist
        CheckoutMoneySnapshot moneySnapshot = CheckoutMoneySnapshot.from(subtotal, benefitSnapshot);
        return new CheckoutQuote(id.value(), cartVersion, addressId.value(), addressVersion, List.copyOf(lines),
                itemCount, subtotal, "INR", now, now.plus(Duration.ofSeconds(properties.getQuoteTtlSeconds())),
                benefitSnapshot, moneySnapshot);
    }

    // ---------- identity / address ----------

    private static AddressId parseAddressId(String raw) {
        try {
            return new AddressId(raw);
        } catch (IllegalArgumentException e) {
            throw new CheckoutFailure(CheckoutFailure.Reason.NOT_FOUND); // malformed == unknown
        }
    }

    private ValidatedAddress resolveAddress(CustomerId customerId, AddressId addressId) {
        Document doc = addresses.findOwnedById(customerId.value(), addressId.value());
        if (doc == null) {
            throw new CheckoutFailure(CheckoutFailure.Reason.NOT_FOUND); // foreign == unknown
        }
        long version = doc.get("version", Number.class).longValue();
        LocationQuery location = LocationQuery.ofPin(new Pincode(doc.getString("postalCode")));
        return new ValidatedAddress(addressId, version, location);
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
