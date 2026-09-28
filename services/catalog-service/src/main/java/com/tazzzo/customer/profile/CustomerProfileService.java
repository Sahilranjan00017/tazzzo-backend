package com.tazzzo.customer.profile;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Function;

/**
 * PR-12A — GET/PATCH orchestration for one customer's own profile. Trusts the caller's
 * {@code customerId} completely: it is only ever supplied by the controller from an already-verified
 * {@code CustomerPrincipal} (never request input), and by the time {@code CustomerAuthFilter} plus
 * {@code SessionAuthority} have admitted the request, PR-11C's own transactional invariant already
 * guarantees the customer identity behind that principal was created — this service performs no
 * separate customer-existence check and never fabricates one; there is no code path in which it
 * would legitimately need to.
 *
 * <p>No direct wall-clock read anywhere in this class — every timestamp comes from the injected
 * {@link Clock}, read once per call.
 */
@Service
public class CustomerProfileService {

    private static final Logger log = LoggerFactory.getLogger(CustomerProfileService.class);

    private final CustomerProfileRepository repository;
    private final Clock clock;
    private final CustomerProfileObservability observability;

    public CustomerProfileService(CustomerProfileRepository repository, Clock clock,
                                  CustomerProfileObservability observability) {
        this.repository = repository;
        this.clock = clock;
        this.observability = observability;
    }

    /** Never creates a profile document merely because it was read — an absent profile returns a
     *  deterministic default projection at version 0. */
    public ProfileView get(String customerId) {
        try {
            Document doc = repository.findById(customerId);
            ProfileView view = doc == null ? ProfileView.absent(customerId) : ProfileView.from(doc);
            observability.readSuccess();
            return view;
        } catch (RuntimeException e) {
            log.error("customer_profile_read_failed", e);
            observability.readFailure(CustomerProfileFailure.Reason.UNAVAILABLE);
            throw new CustomerProfileFailure(CustomerProfileFailure.Reason.UNAVAILABLE);
        }
    }

    /** Lazily creates the profile on first PATCH ({@code expectedVersion == 0}); otherwise a pure
     *  conditional update. Only fields PRESENT in the patch are touched. */
    public ProfileView patch(String customerId, long expectedVersion, PatchField<String> rawDisplayName,
                             PatchField<String> rawEmail) {
        try {
            PatchField<String> displayName = normalize(rawDisplayName, DisplayNames::normalize);
            PatchField<String> email = normalize(rawEmail, Emails::normalize);
            Instant now = clock.instant();
            Document updated = repository.patch(customerId, expectedVersion, displayName, email, now);
            if (updated == null) {
                throw new CustomerProfileFailure(CustomerProfileFailure.Reason.PRECONDITION_FAILED);
            }
            observability.updateSuccess();
            return ProfileView.from(updated);
        } catch (CustomerProfileFailure e) {
            observability.updateFailure(e.reason());
            throw e;
        } catch (RuntimeException e) {
            log.error("customer_profile_update_failed", e);
            observability.updateFailure(CustomerProfileFailure.Reason.UNAVAILABLE);
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
