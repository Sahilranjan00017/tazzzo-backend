package com.tazzzo.account;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.otp.OtpErasure;
import com.tazzzo.auth.session.CustomerAccountErasure;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.audit.DomainEvent;
import com.tazzzo.customer.address.AddressErasure;
import com.tazzzo.customer.cart.CartErasure;
import com.tazzzo.customer.checkout.CheckoutQuoteErasure;
import com.tazzzo.customer.order.OrderErasure;
import com.tazzzo.customer.profile.CustomerProfileErasure;
import com.tazzzo.membership.MembershipErasure;
import com.tazzzo.notification.NotificationErasure;
import com.tazzzo.support.SupportErasure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Customer account deletion (erasure), one transaction. The orchestrator sequences the modules; each module erases its
 * own data (it knows its own personal fields and invariants). Everything commits together or nothing does.
 *
 * <p>What happens, in order, for a customer whose row is ACTIVE:
 * <ol>
 *   <li>every session is revoked: all access and refresh tokens stop working at the next request;</li>
 *   <li>profile (display name, email), saved addresses and the address-state row, cart and checkout quotes are DELETED;</li>
 *   <li>orders are RETAINED as commercial records with the address snapshot ANONYMISED (name, phone, street replaced;
 *       landmark and coordinates removed; postal area kept); the opaque customer id stays as their key;</li>
 *   <li>a currently-entitling membership term is revoked;</li>
 *   <li>support cases (free text written by the customer and staff) and every notification-outbox row are DELETED;</li>
 *   <li>the customer row becomes a TOMBSTONE (status DELETED, phone replaced by a per-id placeholder), so the phone is
 *       free to register again as a NEW customer; the OTP rows of that phone are removed;</li>
 *   <li>a {@code CUSTOMER_ACCOUNT_DELETED} event with counts only (never personal data) is appended to the audit ledger.</li>
 * </ol>
 * Idempotent: a customer already DELETED yields {@link Outcome#ALREADY_DELETED} without any write; two concurrent
 * deletions serialise on the conditional tombstone (the loser retries its transaction and sees DELETED).
 * Retention periods are NOT invented here: what is deleted versus retained is the explicit policy above, recorded in
 * {@code docs/database/DATABASE_RETENTION_AND_PII.md}.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);
    public static final String EVENT_TYPE = "CUSTOMER_ACCOUNT_DELETED";
    public static final Actor ERASURE_ACTOR = Actor.system("system:customer-erasure");

    public enum Outcome { DELETED, ALREADY_DELETED }

    private final Tx tx;
    private final Clock clock;
    private final CustomerAccountErasure account;
    private final OtpErasure otp;
    private final CustomerProfileErasure profile;
    private final AddressErasure addresses;
    private final CartErasure cart;
    private final CheckoutQuoteErasure quotes;
    private final OrderErasure orders;
    private final MembershipErasure membership;
    private final SupportErasure support;
    private final NotificationErasure notifications;
    private final DomainAudit audit;
    private final AccountDeletionObservability observability;

    @Autowired
    public AccountDeletionService(Tx tx, MongoDatabase db, CustomerAccountErasure account, OtpErasure otp,
                                  CustomerProfileErasure profile, AddressErasure addresses, CartErasure cart,
                                  CheckoutQuoteErasure quotes, OrderErasure orders, MembershipErasure membership,
                                  SupportErasure support, NotificationErasure notifications,
                                  AccountDeletionObservability observability) {
        this(tx, Clock.systemUTC(), account, otp, profile, addresses, cart, quotes, orders, membership, support, notifications,
                new DomainAudit(db, Clock.systemUTC()), observability);
    }

    AccountDeletionService(Tx tx, Clock clock, CustomerAccountErasure account, OtpErasure otp, CustomerProfileErasure profile,
                           AddressErasure addresses, CartErasure cart, CheckoutQuoteErasure quotes, OrderErasure orders,
                           MembershipErasure membership, SupportErasure support, NotificationErasure notifications,
                           DomainAudit audit, AccountDeletionObservability observability) {
        this.support = support;
        this.notifications = notifications;
        this.tx = tx;
        this.clock = clock;
        this.account = account;
        this.otp = otp;
        this.profile = profile;
        this.addresses = addresses;
        this.cart = cart;
        this.quotes = quotes;
        this.orders = orders;
        this.membership = membership;
        this.audit = audit;
        this.observability = observability;
    }

    public Outcome delete(CustomerId customerId) {
        Outcome outcome;
        try {
            outcome = tx.call(session -> deleteInSession(session, customerId));
        } catch (AccountDeletionFailure e) {
            observability.failure(e.reason());
            throw e;
        } catch (RuntimeException e) {
            log.error("customer_account_deletion_failed type={}", e.getClass().getSimpleName());
            observability.failure(AccountDeletionFailure.Reason.UNAVAILABLE);
            throw new AccountDeletionFailure(AccountDeletionFailure.Reason.UNAVAILABLE);
        }
        if (outcome == Outcome.DELETED) {
            observability.deleted();
        } else {
            observability.alreadyDeleted();
        }
        return outcome;
    }

    /** One attempt; the transaction may retry it, so every step is idempotent. */
    private Outcome deleteInSession(ClientSession session, CustomerId customerId) {
        Instant now = clock.instant();
        switch (account.state(session, customerId)) {
            case MISSING -> throw new AccountDeletionFailure(AccountDeletionFailure.Reason.UNAVAILABLE); // a token for no customer
            case DELETED -> {
                return Outcome.ALREADY_DELETED;
            }
            case ACTIVE -> { }
        }
        String id = customerId.value();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("profiles", profile.erase(session, id));
        counts.put("addresses", addresses.erase(session, id));
        counts.put("carts", cart.erase(session, id));
        counts.put("quotes", quotes.erase(session, id));
        counts.put("ordersAnonymised", orders.anonymise(session, id, now));
        counts.put("membership", membership.revokeOpenTerm(session, customerId, now).name());
        counts.put("supportCases", support.erase(session, id));
        counts.put("notifications", notifications.erase(session, id));
        CustomerAccountErasure.Result identity = account.erase(session, customerId, now);
        if (identity.phone().isEmpty()) {
            // the row stopped being ACTIVE between our read and the tombstone: a concurrent deletion won
            return Outcome.ALREADY_DELETED;
        }
        counts.put("sessionsRevoked", identity.sessionsRevoked());
        counts.put("otpRows", otp.purgeForPhone(session, identity.phone().get()));
        audit.append(session, new DomainEvent("customer", id, EVENT_TYPE, counts, ERASURE_ACTOR));
        return Outcome.DELETED;
    }
}
