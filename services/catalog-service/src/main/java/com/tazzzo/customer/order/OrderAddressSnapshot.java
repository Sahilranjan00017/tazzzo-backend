package com.tazzzo.customer.order;

/**
 * PR-14B — the immutable delivery-address snapshot frozen from the version-validated
 * {@code customer_addresses} document at the moment the Order transaction committed. A later edit
 * to the source address (even a full delete) never changes this snapshot — the same "frozen at
 * commit, never re-read" discipline {@link OrderLine} follows for product display data.
 *
 * <p>Fields mirror {@code AddressRepository}'s stored document exactly — no invented fields.
 * {@code latitude}/{@code longitude} are carried for INTERNAL order history/fulfilment routing
 * only; no customer-facing HTTP DTO exists in this PR, so no coordinate-leak boundary is needed
 * yet (a future public Order surface must exclude them explicitly, the same way
 * {@code CheckoutQuoteDto} excludes {@code addressVersion}).
 */
public record OrderAddressSnapshot(String label, String recipientName, String recipientPhone, String addressLine1,
                                   String addressLine2, String landmark, String city, String state,
                                   String postalCode, double latitude, double longitude) {

    public OrderAddressSnapshot {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label required");
        }
        if (recipientName == null || recipientName.isBlank()) {
            throw new IllegalArgumentException("recipientName required");
        }
        if (recipientPhone == null || recipientPhone.isBlank()) {
            throw new IllegalArgumentException("recipientPhone required");
        }
        if (addressLine1 == null || addressLine1.isBlank()) {
            throw new IllegalArgumentException("addressLine1 required");
        }
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("city required");
        }
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state required");
        }
        if (postalCode == null || postalCode.isBlank()) {
            throw new IllegalArgumentException("postalCode required");
        }
        // addressLine2/landmark are nullable, mirroring the source Address document's own
        // nullability -- this snapshot invents no stricter contract than its source.
    }
}
