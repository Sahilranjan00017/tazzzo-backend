package com.tazzzo.customer.cart;

import com.tazzzo.auth.CustomerId;
import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.customer.address.AddressId;
import com.tazzzo.customer.address.AddressRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PR-12C — the optional delivery-location context for cart enrichment. The ONLY supported input is
 * a SAVED ADDRESS ({@code ?addressId=}), looked up ownership-scoped (customerId AND addressId); a
 * foreign, unknown or malformed id is the identical NOT_FOUND. The address's postalCode becomes the
 * existing commerce {@link LocationQuery} (PIN is the only location form the current serviceability
 * contract resolves), which then flows path: location -> Serviceability -> internal
 * fulfillmentLocationId -> Inventory INSIDE the existing enricher. This class never sees, stores or
 * returns a fulfillment location. No parallel PIN/lat-lng parser is invented.
 */
@Component
public class CartLocationResolver {

    private static final Logger log = LoggerFactory.getLogger(CartLocationResolver.class);

    private final AddressRepository addresses;

    public CartLocationResolver(AddressRepository addresses) {
        this.addresses = addresses;
    }

    public LocationQuery resolve(CustomerId customerId, String addressIdRaw) {
        if (addressIdRaw == null) {
            return LocationQuery.anonymous();
        }
        AddressId addressId;
        try {
            addressId = new AddressId(addressIdRaw);
        } catch (IllegalArgumentException e) {
            throw new CartFailure(CartFailure.Reason.NOT_FOUND);
        }
        try {
            Document doc = addresses.findOwnedById(customerId.value(), addressId.value());
            if (doc == null) {
                throw new CartFailure(CartFailure.Reason.NOT_FOUND);
            }
            return LocationQuery.ofPin(new Pincode(doc.getString("postalCode")));
        } catch (CartFailure e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("customer_cart_location_failed type={}", e.getClass().getSimpleName());
            throw new CartFailure(CartFailure.Reason.UNAVAILABLE);
        }
    }
}
