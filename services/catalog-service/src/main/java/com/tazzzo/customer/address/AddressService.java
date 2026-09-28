package com.tazzzo.customer.address;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * PR-12B — CRUD + set-default orchestration for one customer's own saved addresses.
 *
 * <p><b>Identity integrity</b> (the PR-12A pattern, reused unchanged): every mutation verifies the
 * authenticated customer identity exists via {@link CustomerIdentityAuthority}, transactionally
 * folded into the SAME {@link Tx#call}/{@link Tx#run} as the address mutation itself — a missing
 * identity throws INSIDE the callback, aborting before any write. Reads (GET/LIST) verify
 * non-transactionally, since they can never create persisted state.
 *
 * <p><b>Concurrency invariants</b> (limit + single default) are enforced entirely through
 * {@link CustomerAddressStateRepository} — see its class-level doc for why a single per-customer
 * document, not per-address fields, is what makes both invariants safe under real concurrent writes.
 *
 * <p><b>Retry safety</b> (mission §33/§34): the {@link AddressId} is generated ONCE, before entering
 * any transaction, and reused across a driver-initiated retry — a retried attempt inserts the SAME
 * logical address, never a duplicate. No mutable holder is used outside any transaction callback;
 * every transactional method returns its result straight from {@link Tx#call}.
 */
@Service
public class AddressService {

    private static final Logger log = LoggerFactory.getLogger(AddressService.class);

    private final AddressRepository addresses;
    private final CustomerAddressStateRepository state;
    private final AddressLimitProperties limits;
    private final Clock clock;
    private final AddressObservability observability;
    private final ObjectProvider<CustomerIdentityAuthority> identityAuthority;
    private final Tx tx;

    public AddressService(AddressRepository addresses, CustomerAddressStateRepository state,
                          AddressLimitProperties limits, Clock clock, AddressObservability observability,
                          ObjectProvider<CustomerIdentityAuthority> identityAuthority, Tx tx) {
        this.addresses = addresses;
        this.state = state;
        this.limits = limits;
        this.clock = clock;
        this.observability = observability;
        this.identityAuthority = identityAuthority;
        this.tx = tx;
    }

    // ---------- reads ----------

    public List<AddressView> list(CustomerId customerId) {
        try {
            verifyIdentityExistsNonTransactional(customerId);
            List<Document> docs = addresses.findAllByCustomer(customerId.value());
            String defaultId = currentDefaultId(customerId.value());
            List<AddressView> views = new ArrayList<>(docs.size());
            for (Document d : docs) {
                views.add(toView(d, defaultId));
            }
            // stable sort: default first, everything else keeps the repository's own
            // (updatedAt desc, _id asc) order -- at most one entry can ever be "default".
            views.sort(Comparator.comparing(v -> v.isDefault() ? 0 : 1));
            observability.listSuccess();
            return views;
        } catch (AddressFailure e) {
            observability.failure(AddressObservability.Operation.LIST, e.reason());
            throw e;
        } catch (RuntimeException e) {
            log.error("customer_address_list_failed type={}", e.getClass().getSimpleName());
            observability.failure(AddressObservability.Operation.LIST, AddressFailure.Reason.UNAVAILABLE);
            throw new AddressFailure(AddressFailure.Reason.UNAVAILABLE);
        }
    }

    public AddressView get(CustomerId customerId, AddressId addressId) {
        try {
            verifyIdentityExistsNonTransactional(customerId);
            Document doc = addresses.findOwnedById(customerId.value(), addressId.value());
            if (doc == null) {
                throw new AddressFailure(AddressFailure.Reason.NOT_FOUND);
            }
            AddressView view = toView(doc, currentDefaultId(customerId.value()));
            observability.readSuccess();
            return view;
        } catch (AddressFailure e) {
            observability.failure(AddressObservability.Operation.READ, e.reason());
            throw e;
        } catch (RuntimeException e) {
            log.error("customer_address_read_failed type={}", e.getClass().getSimpleName());
            observability.failure(AddressObservability.Operation.READ, AddressFailure.Reason.UNAVAILABLE);
            throw new AddressFailure(AddressFailure.Reason.UNAVAILABLE);
        }
    }

    // ---------- create ----------

    public AddressView create(CustomerId customerId, CreateCommand cmd) {
        AddressLabel label = AddressLabel.parse(cmd.label());
        String recipientName = AddressTexts.required(cmd.recipientName(), 80);
        String recipientPhone = RecipientPhone.normalize(cmd.recipientPhone());
        String addressLine1 = AddressTexts.required(cmd.addressLine1(), 160);
        String addressLine2 = AddressTexts.optional(cmd.addressLine2(), 160);
        String landmark = AddressTexts.optional(cmd.landmark(), 120);
        String city = AddressTexts.required(cmd.city(), 80);
        String stateName = AddressTexts.required(cmd.state(), 80);
        String postalCode = PostalCode.normalize(cmd.postalCode());
        Coordinates.Pair coords = Coordinates.validate(cmd.latitude(), cmd.longitude());

        AddressId addressId = AddressId.generate(); // outside the transaction -- retry-safe (mission §34)
        Instant now = clock.instant();

        try {
            Document created = tx.call(session -> {
                verifyIdentityExistsTransactional(session, customerId);
                Document stateDoc = state.incrementIfBelowLimit(session, customerId.value(),
                        limits.getMaxActiveAddresses(), now);
                if (stateDoc == null) {
                    throw new AddressFailure(AddressFailure.Reason.ADDRESS_LIMIT_REACHED);
                }
                boolean isFirst = stateDoc.get("addressCount", Number.class).longValue() == 1L;

                Document doc = new Document()
                        .append("_id", addressId.value())
                        .append("customerId", customerId.value())
                        .append("label", label.name())
                        .append("recipientName", recipientName)
                        .append("recipientPhone", recipientPhone)
                        .append("addressLine1", addressLine1)
                        .append("addressLine2", addressLine2)
                        .append("landmark", landmark)
                        .append("city", city)
                        .append("state", stateName)
                        .append("postalCode", postalCode)
                        .append("latitude", coords.latitude())
                        .append("longitude", coords.longitude())
                        .append("version", 1L)
                        .append("createdAt", Date.from(now))
                        .append("updatedAt", Date.from(now));
                addresses.insert(session, doc);
                if (isFirst) {
                    state.setDefault(session, customerId.value(), addressId.value(), now);
                }
                return doc;
            });
            observability.createSuccess(); // ONLY after Tx.call returns successfully (mission §33)
            return toView(created, currentDefaultId(customerId.value()));
        } catch (AddressFailure e) {
            observability.failure(AddressObservability.Operation.CREATE, e.reason());
            throw e;
        } catch (RuntimeException e) {
            log.error("customer_address_create_failed type={}", e.getClass().getSimpleName());
            observability.failure(AddressObservability.Operation.CREATE, AddressFailure.Reason.UNAVAILABLE);
            throw new AddressFailure(AddressFailure.Reason.UNAVAILABLE);
        }
    }

    // ---------- patch ----------

    public AddressView patch(CustomerId customerId, AddressId addressId, long expectedVersion, PatchCommand cmd) {
        PatchField<AddressLabel> label = requiredPatch(cmd.label(), v -> AddressLabel.parse(v));
        PatchField<String> recipientName = requiredTextPatch(cmd.recipientName(), 80);
        PatchField<String> recipientPhone = requiredPatch(cmd.recipientPhone(), RecipientPhone::normalize);
        PatchField<String> addressLine1 = requiredTextPatch(cmd.addressLine1(), 160);
        PatchField<String> addressLine2 = optionalTextPatch(cmd.addressLine2(), 160);
        PatchField<String> landmark = optionalTextPatch(cmd.landmark(), 120);
        PatchField<String> city = requiredTextPatch(cmd.city(), 80);
        PatchField<String> stateName = requiredTextPatch(cmd.state(), 80);
        PatchField<String> postalCode = requiredPatch(cmd.postalCode(), PostalCode::normalize);
        PatchField<Coordinates.Pair> coordinates = coordinatesPatch(cmd.latitude(), cmd.longitude());

        if (!label.isPresent() && !recipientName.isPresent() && !recipientPhone.isPresent()
                && !addressLine1.isPresent() && !addressLine2.isPresent() && !landmark.isPresent()
                && !city.isPresent() && !stateName.isPresent() && !postalCode.isPresent()
                && !coordinates.isPresent()) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }

        Instant now = clock.instant();
        try {
            Document updated = tx.call(session -> {
                verifyIdentityExistsTransactional(session, customerId);
                Document existing = addresses.findOwnedById(session, customerId.value(), addressId.value());
                if (existing == null) {
                    throw new AddressFailure(AddressFailure.Reason.NOT_FOUND);
                }
                long actualVersion = existing.get("version", Number.class).longValue();
                if (actualVersion != expectedVersion) {
                    throw new AddressFailure(AddressFailure.Reason.PRECONDITION_FAILED);
                }
                Document result = addresses.patch(session, customerId.value(), addressId.value(), expectedVersion,
                        label, recipientName, recipientPhone, addressLine1, addressLine2, landmark, city, stateName,
                        postalCode, coordinates, now);
                if (result == null) {
                    // Defense-in-depth: the version filter is re-applied at write time even though
                    // we just confirmed it above in the SAME transaction/session.
                    throw new AddressFailure(AddressFailure.Reason.PRECONDITION_FAILED);
                }
                return result;
            });
            observability.updateSuccess();
            return toView(updated, currentDefaultId(customerId.value()));
        } catch (AddressFailure e) {
            observability.failure(AddressObservability.Operation.UPDATE, e.reason());
            throw e;
        } catch (RuntimeException e) {
            log.error("customer_address_update_failed type={}", e.getClass().getSimpleName());
            observability.failure(AddressObservability.Operation.UPDATE, AddressFailure.Reason.UNAVAILABLE);
            throw new AddressFailure(AddressFailure.Reason.UNAVAILABLE);
        }
    }

    // ---------- delete ----------

    /** Deleting the current default deterministically promotes the most-recently-updated
     *  remaining address; zero addresses remaining means zero default. Both happen in the SAME
     *  transaction as the delete itself. */
    public void delete(CustomerId customerId, AddressId addressId, long expectedVersion) {
        Instant now = clock.instant();
        try {
            tx.run(session -> {
                verifyIdentityExistsTransactional(session, customerId);
                Document existing = addresses.findOwnedById(session, customerId.value(), addressId.value());
                if (existing == null) {
                    throw new AddressFailure(AddressFailure.Reason.NOT_FOUND);
                }
                long actualVersion = existing.get("version", Number.class).longValue();
                if (actualVersion != expectedVersion) {
                    throw new AddressFailure(AddressFailure.Reason.PRECONDITION_FAILED);
                }
                boolean deleted = addresses.deleteOwned(session, customerId.value(), addressId.value(),
                        expectedVersion);
                if (!deleted) {
                    throw new AddressFailure(AddressFailure.Reason.PRECONDITION_FAILED);
                }
                state.decrement(session, customerId.value(), now);

                Document stateDoc = state.findByCustomerId(session, customerId.value());
                boolean wasDefault = stateDoc != null && addressId.value().equals(stateDoc.getString("defaultAddressId"));
                if (wasDefault) {
                    List<Document> remaining = addresses.findAllByCustomer(session, customerId.value());
                    String replacement = remaining.isEmpty() ? null : remaining.get(0).getString("_id");
                    state.setDefault(session, customerId.value(), replacement, now);
                }
            });
            observability.deleteSuccess();
        } catch (AddressFailure e) {
            observability.failure(AddressObservability.Operation.DELETE, e.reason());
            throw e;
        } catch (RuntimeException e) {
            log.error("customer_address_delete_failed type={}", e.getClass().getSimpleName());
            observability.failure(AddressObservability.Operation.DELETE, AddressFailure.Reason.UNAVAILABLE);
            throw new AddressFailure(AddressFailure.Reason.UNAVAILABLE);
        }
    }

    // ---------- set default ----------

    public AddressView setDefault(CustomerId customerId, AddressId addressId) {
        Instant now = clock.instant();
        try {
            tx.run(session -> {
                verifyIdentityExistsTransactional(session, customerId);
                Document existing = addresses.findOwnedById(session, customerId.value(), addressId.value());
                if (existing == null) {
                    throw new AddressFailure(AddressFailure.Reason.NOT_FOUND);
                }
                state.setDefault(session, customerId.value(), addressId.value(), now);
            });
            observability.setDefaultSuccess();
            Document doc = addresses.findOwnedById(customerId.value(), addressId.value());
            return toView(doc, addressId.value());
        } catch (AddressFailure e) {
            observability.failure(AddressObservability.Operation.SET_DEFAULT, e.reason());
            throw e;
        } catch (RuntimeException e) {
            log.error("customer_address_set_default_failed type={}", e.getClass().getSimpleName());
            observability.failure(AddressObservability.Operation.SET_DEFAULT, AddressFailure.Reason.UNAVAILABLE);
            throw new AddressFailure(AddressFailure.Reason.UNAVAILABLE);
        }
    }

    // ---------- identity integrity ----------

    private void verifyIdentityExistsNonTransactional(CustomerId customerId) {
        CustomerIdentityAuthority authority = requireAuthority();
        boolean exists;
        try {
            exists = authority.exists(customerId);
        } catch (RuntimeException e) {
            log.error("customer_identity_authority_failed type={}", e.getClass().getSimpleName());
            throw new AddressFailure(AddressFailure.Reason.UNAVAILABLE);
        }
        if (!exists) {
            throw new AddressFailure(AddressFailure.Reason.UNAVAILABLE);
        }
    }

    private void verifyIdentityExistsTransactional(ClientSession session, CustomerId customerId) {
        CustomerIdentityAuthority authority = requireAuthority();
        boolean exists;
        try {
            exists = authority.exists(session, customerId);
        } catch (RuntimeException e) {
            log.error("customer_identity_authority_failed type={}", e.getClass().getSimpleName());
            throw new AddressFailure(AddressFailure.Reason.UNAVAILABLE);
        }
        if (!exists) {
            throw new AddressFailure(AddressFailure.Reason.UNAVAILABLE);
        }
    }

    private CustomerIdentityAuthority requireAuthority() {
        CustomerIdentityAuthority authority = identityAuthority.getIfAvailable();
        if (authority == null) {
            throw new AddressFailure(AddressFailure.Reason.UNAVAILABLE);
        }
        return authority;
    }

    // ---------- helpers ----------

    private String currentDefaultId(String customerId) {
        Document stateDoc = state.findByCustomerId(customerId);
        return stateDoc == null ? null : stateDoc.getString("defaultAddressId");
    }

    private static PatchField<String> requiredTextPatch(PatchField<String> raw, int maxCodePoints) {
        return requiredPatch(raw, v -> AddressTexts.required(v, maxCodePoints));
    }

    private static PatchField<String> optionalTextPatch(PatchField<String> raw, int maxCodePoints) {
        if (!raw.isPresent()) {
            return PatchField.absent();
        }
        return PatchField.of(AddressTexts.optional(raw.value(), maxCodePoints));
    }

    /** A required field's PATCH value can never be explicit-null (mission §18). */
    private static <T> PatchField<T> requiredPatch(PatchField<String> raw, java.util.function.Function<String, T> parse) {
        if (!raw.isPresent()) {
            return PatchField.absent();
        }
        if (raw.value() == null) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        return PatchField.of(parse.apply(raw.value()));
    }

    private static PatchField<Coordinates.Pair> coordinatesPatch(PatchField<Double> latitude,
                                                                  PatchField<Double> longitude) {
        if (!latitude.isPresent() && !longitude.isPresent()) {
            return PatchField.absent();
        }
        if (latitude.isPresent() != longitude.isPresent()) {
            // Only one of the pair's two PATCH keys was supplied -- always ambiguous (mission §11/§18).
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        return PatchField.of(Coordinates.validate(latitude.value(), longitude.value()));
    }

    private static AddressView toView(Document doc, String defaultAddressId) {
        String addressId = doc.getString("_id");
        return new AddressView(
                addressId,
                AddressLabel.valueOf(doc.getString("label")),
                doc.getString("recipientName"),
                doc.getString("recipientPhone"),
                doc.getString("addressLine1"),
                doc.getString("addressLine2"),
                doc.getString("landmark"),
                doc.getString("city"),
                doc.getString("state"),
                doc.getString("postalCode"),
                doc.get("latitude", Double.class),
                doc.get("longitude", Double.class),
                addressId.equals(defaultAddressId),
                doc.get("version", Number.class).longValue());
    }

    public record CreateCommand(String label, String recipientName, String recipientPhone, String addressLine1,
                                String addressLine2, String landmark, String city, String state, String postalCode,
                                Double latitude, Double longitude) {
    }

    public record PatchCommand(PatchField<String> label, PatchField<String> recipientName,
                               PatchField<String> recipientPhone, PatchField<String> addressLine1,
                               PatchField<String> addressLine2, PatchField<String> landmark, PatchField<String> city,
                               PatchField<String> state, PatchField<String> postalCode, PatchField<Double> latitude,
                               PatchField<Double> longitude) {
    }

    public record AddressView(String addressId, AddressLabel label, String recipientName, String recipientPhone,
                              String addressLine1, String addressLine2, String landmark, String city, String state,
                              String postalCode, Double latitude, Double longitude, boolean isDefault, long version) {
    }
}
