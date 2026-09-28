package com.tazzzo.customer.profile;

import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.catalog.tx.Tx;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Function;

/**
 * PR-12A — GET/PATCH orchestration for one customer's own profile.
 *
 * <p><b>Identity integrity (Finding 1 hardening).</b> An authenticated {@code CustomerPrincipal}
 * proves the BEARER TOKEN and SESSION were valid at verification time — it does NOT, by itself,
 * guarantee the referenced customer identity still exists in the auth-owned {@code customers}
 * collection (a data-integrity corruption, never a normal user-facing state). Every GET/PATCH here
 * therefore confirms real existence via {@link CustomerIdentityAuthority} BEFORE returning an
 * absent/default profile or creating/updating any profile state. A missing identity, or the
 * authority itself failing, is a SERVER data-integrity/readiness problem — mapped to
 * {@code UNAVAILABLE} (503), never a fabricated default profile and never a fake 401.
 *
 * <p><b>TOCTOU close (final hardening).</b> For PATCH, the identity check and the profile write are
 * NOT two separate round-trips — both run on the SAME {@link ClientSession} inside ONE Mongo
 * transaction via {@link Tx#call}, so "the identity exists" and "the profile is written" are decided
 * from the SAME transactional snapshot. If the identity is missing, the callback THROWS before any
 * write is attempted, aborting the transaction — never a ghost {@code customer_profiles} document.
 * GET stays read-only and non-transactional (see {@link #get}): it can never create persisted state,
 * so a momentary race there is inherently transient, not a data-integrity concern.
 *
 * <p><b>Retry-safe by construction.</b> {@code ClientSession.withTransaction} may invoke its body
 * more than once (a transient-transaction-error retry — the PR-11B/PR-11C lesson). {@link Tx#call}
 * returns T straight from the driver's own retry loop; this class never stashes an intermediate
 * result in a mutable holder outside the transaction callback.
 *
 * <p>No direct wall-clock read anywhere in this class — every timestamp comes from the injected
 * {@link Clock}, read once per call. Failures are logged with the exception TYPE only, never the
 * exception object itself — this domain carries PII (displayName, email), and a driver/runtime
 * exception message or stack context must never be assumed safe to log verbatim.
 */
@Service
public class CustomerProfileService {

    private static final Logger log = LoggerFactory.getLogger(CustomerProfileService.class);

    private final CustomerProfileRepository repository;
    private final Clock clock;
    private final CustomerProfileObservability observability;
    private final ObjectProvider<CustomerIdentityAuthority> identityAuthority;
    private final Tx tx;

    public CustomerProfileService(CustomerProfileRepository repository, Clock clock,
                                  CustomerProfileObservability observability,
                                  ObjectProvider<CustomerIdentityAuthority> identityAuthority, Tx tx) {
        this.repository = repository;
        this.clock = clock;
        this.observability = observability;
        this.identityAuthority = identityAuthority;
        this.tx = tx;
    }

    /** Never creates a profile document merely because it was read — an absent profile returns a
     *  deterministic default projection at version 0. Never fabricated for a ghost identity.
     *
     *  <p>Read-only and NOT transactional: GET can never create persisted state, so a customer
     *  identity disappearing between the existence check and the profile read is a purely transient
     *  response-level race, not a data-integrity concern (nothing is ever written here). */
    public ProfileView get(CustomerId customerId) {
        try {
            verifyIdentityExistsNonTransactional(customerId);
            Document doc = repository.findById(customerId.value());
            ProfileView view = doc == null ? ProfileView.absent(customerId.value()) : ProfileView.from(doc);
            observability.readSuccess();
            return view;
        } catch (CustomerProfileFailure e) {
            observability.readFailure(e.reason());
            throw e;
        } catch (RuntimeException e) {
            log.error("customer_profile_read_failed type={}", e.getClass().getSimpleName());
            observability.readFailure(CustomerProfileFailure.Reason.UNAVAILABLE);
            throw new CustomerProfileFailure(CustomerProfileFailure.Reason.UNAVAILABLE);
        }
    }

    /** Lazily creates the profile on first PATCH ({@code expectedVersion == 0}); otherwise a pure
     *  conditional update. Only fields PRESENT in the patch are touched. The identity-existence
     *  check and the write are ONE transaction — a missing identity throws INSIDE the callback, so
     *  no profile document can ever be committed for it. */
    public ProfileView patch(CustomerId customerId, long expectedVersion, PatchField<String> rawDisplayName,
                             PatchField<String> rawEmail) {
        try {
            PatchField<String> displayName = normalize(rawDisplayName, DisplayNames::normalize);
            PatchField<String> email = normalize(rawEmail, Emails::normalize);
            Instant now = clock.instant();
            Document updated = tx.call(session -> {
                verifyIdentityExistsTransactional(session, customerId);
                Document result = repository.patch(session, customerId.value(), expectedVersion, displayName,
                        email, now);
                if (result == null) {
                    throw new CustomerProfileFailure(CustomerProfileFailure.Reason.PRECONDITION_FAILED);
                }
                return result;
            });
            observability.updateSuccess();
            return ProfileView.from(updated);
        } catch (CustomerProfileFailure e) {
            observability.updateFailure(e.reason());
            throw e;
        } catch (RuntimeException e) {
            log.error("customer_profile_update_failed type={}", e.getClass().getSimpleName());
            observability.updateFailure(CustomerProfileFailure.Reason.UNAVAILABLE);
            throw new CustomerProfileFailure(CustomerProfileFailure.Reason.UNAVAILABLE);
        }
    }

    /**
     * @throws CustomerProfileFailure UNAVAILABLE -- the identity authority is absent, fails, or
     *         confirms the customer identity does not exist. Never a 401 (that is a credential
     *         concern, already settled by {@code CustomerAuthFilter} before this runs) and never a
     *         404 (this is a data-integrity failure, not a user-visible absence).
     */
    private void verifyIdentityExistsNonTransactional(CustomerId customerId) {
        CustomerIdentityAuthority authority = requireAuthority();
        boolean exists;
        try {
            exists = authority.exists(customerId);
        } catch (RuntimeException e) {
            log.error("customer_identity_authority_failed type={}", e.getClass().getSimpleName());
            throw new CustomerProfileFailure(CustomerProfileFailure.Reason.UNAVAILABLE);
        }
        if (!exists) {
            throw new CustomerProfileFailure(CustomerProfileFailure.Reason.UNAVAILABLE);
        }
    }

    /** Same contract as {@link #verifyIdentityExistsNonTransactional}, but reads on the SAME
     *  {@link ClientSession} the caller's transaction is already using -- called from INSIDE a
     *  {@link Tx#call} callback, so throwing here aborts the whole transaction before any write. */
    private void verifyIdentityExistsTransactional(ClientSession session, CustomerId customerId) {
        CustomerIdentityAuthority authority = requireAuthority();
        boolean exists;
        try {
            exists = authority.exists(session, customerId);
        } catch (RuntimeException e) {
            log.error("customer_identity_authority_failed type={}", e.getClass().getSimpleName());
            throw new CustomerProfileFailure(CustomerProfileFailure.Reason.UNAVAILABLE);
        }
        if (!exists) {
            throw new CustomerProfileFailure(CustomerProfileFailure.Reason.UNAVAILABLE);
        }
    }

    private CustomerIdentityAuthority requireAuthority() {
        CustomerIdentityAuthority authority = identityAuthority.getIfAvailable();
        if (authority == null) {
            throw new CustomerProfileFailure(CustomerProfileFailure.Reason.UNAVAILABLE);
        }
        return authority;
    }

    private static PatchField<String> normalize(PatchField<String> field, Function<String, String> normalizer) {
        if (!field.isPresent()) {
            return field;
        }
        return PatchField.of(normalizer.apply(field.value()));
    }

    public record ProfileView(String customerId, String displayName, String email, long version) {

        static ProfileView absent(String customerId) {
            return new ProfileView(customerId, null, null, 0);
        }

        static ProfileView from(Document doc) {
            return new ProfileView(doc.getString("_id"), doc.getString("displayName"), doc.getString("email"),
                    doc.get("version", Number.class).longValue());
        }
    }
}
