package com.tazzzo.customer.profile;

import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
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

    public CustomerProfileService(CustomerProfileRepository repository, Clock clock,
                                  CustomerProfileObservability observability,
                                  ObjectProvider<CustomerIdentityAuthority> identityAuthority) {
        this.repository = repository;
        this.clock = clock;
        this.observability = observability;
        this.identityAuthority = identityAuthority;
    }

    /** Never creates a profile document merely because it was read — an absent profile returns a
     *  deterministic default projection at version 0. Never fabricated for a ghost identity. */
    public ProfileView get(CustomerId customerId) {
        try {
            verifyIdentityExists(customerId);
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
     *  conditional update. Only fields PRESENT in the patch are touched. Never creates/updates
     *  profile state for a ghost identity. */
    public ProfileView patch(CustomerId customerId, long expectedVersion, PatchField<String> rawDisplayName,
                             PatchField<String> rawEmail) {
        try {
            verifyIdentityExists(customerId);
            PatchField<String> displayName = normalize(rawDisplayName, DisplayNames::normalize);
            PatchField<String> email = normalize(rawEmail, Emails::normalize);
            Instant now = clock.instant();
            Document updated = repository.patch(customerId.value(), expectedVersion, displayName, email, now);
            if (updated == null) {
                throw new CustomerProfileFailure(CustomerProfileFailure.Reason.PRECONDITION_FAILED);
            }
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
    private void verifyIdentityExists(CustomerId customerId) {
        CustomerIdentityAuthority authority = identityAuthority.getIfAvailable();
        if (authority == null) {
            throw new CustomerProfileFailure(CustomerProfileFailure.Reason.UNAVAILABLE);
        }
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
